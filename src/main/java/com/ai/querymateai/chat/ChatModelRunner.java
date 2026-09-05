package com.ai.querymateai.chat;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.security.PrivacySession;
import com.ai.querymateai.trace.LocalAiTraceLogger;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.RateLimitException;

/** Owns model invocation policy, OpenAI options, and structured-output conversion. */
@Component
public class ChatModelRunner {

    private static final Logger logger = LoggerFactory.getLogger(ChatModelRunner.class);

    private static final long RETRY_BACKOFF_MILLIS = 1_200L;

    private static final String STRUCTURED_OUTPUT_ERROR =
            "I couldn't safely interpret the model response. Please try again.";

    private static final String RATE_LIMIT_ERROR =
            "The model provider is rate-limited. Please try again shortly or switch models.";

    private static final String INVALID_DATA_ERROR =
            "The selected model returned an unsupported response format. Try another model or use prompt_json mode.";

    private final ChatClient chatClient;

    private final AppProperties properties;

    private final LocalAiTraceLogger traceLogger;

    private final BeanOutputConverter<ChatResponse.ModelAnswer> answerConverter =
            new BeanOutputConverter<>(ChatResponse.ModelAnswer.class);

    public ChatModelRunner(ChatClient.Builder chatClientBuilder, AppProperties properties,
            LocalAiTraceLogger traceLogger) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.traceLogger = traceLogger;
        logger.info("Provider: OpenAI");
        logger.info("Primary model: {}", properties.models().primary());
        logger.info("Fallback model: {}", properties.models().fallback());
    }

    public Result run(String message, String systemPrompt, List<Message> history, ToolCallback[] tools,
            PrivacySession guardSession, ProgressSink progressSink, String requestId) {
        String primary = this.properties.models().primary();
        String fallback = this.properties.models().fallback();

        try {
            progressSink.progress("model", "Reasoning with " + primary + "\u2026");
            return complete(message, systemPrompt, history, tools, guardSession, primary, false, requestId);
        }
        catch (ModelCallException firstFailure) {
            logModelFailure(firstFailure, requestId);
            if (this.properties.execution().primaryRetryEnabled()) {
                progressSink.progress("retry", "Primary model failed \u2014 retrying once\u2026");
                sleepBeforeRetry();
                try {
                    return complete(message, systemPrompt, history, tools, guardSession, primary, false, requestId);
                }
                catch (ModelCallException retryFailure) {
                    logModelFailure(retryFailure, requestId);
                    return fallbackOrThrow(message, systemPrompt, history, tools, guardSession, progressSink,
                            fallback, "Primary model failed after one retry.", retryFailure, requestId);
                }
            }
            return fallbackOrThrow(message, systemPrompt, history, tools, guardSession, progressSink,
                    fallback, "Primary model failed.", firstFailure, requestId);
        }
    }

    private Result fallbackOrThrow(String message, String systemPrompt, List<Message> history, ToolCallback[] tools,
            PrivacySession guardSession, ProgressSink progressSink, String fallback,
            String primaryFailureMessage, ModelCallException primaryFailure, String requestId) {
        if (!this.properties.execution().fallbackEnabled()) {
            throw chatFailure(userMessageFor(primaryFailureMessage, primaryFailure));
        }
        progressSink.progress("fallback", "Falling back to " + fallback + "\u2026");
        try {
            return complete(message, systemPrompt, history, tools, guardSession, fallback, true, requestId);
        }
        catch (ModelCallException fallbackFailure) {
            logModelFailure(fallbackFailure, requestId);
            throw chatFailure(userMessageFor("Primary model " + this.properties.models().primary()
                    + " and fallback model " + fallback + " both failed.", fallbackFailure));
        }
    }

    private Result complete(String message, String systemPrompt, List<Message> history, ToolCallback[] tools,
            PrivacySession guardSession, String model, boolean fallbackUsed, String requestId) {
        long startedAt = System.nanoTime();
        org.springframework.ai.chat.model.ChatResponse response;
        this.traceLogger.traceLlmRequest(requestId, "OpenAI", model, systemPrompt(systemPrompt), history, message, tools);
        try {
            response = this.chatClient.prompt()
                    .system(systemPrompt(systemPrompt))
                    .messages(history)
                    .user(message)
                    .options(chatOptions(model, tools))
                    .call()
                    .chatResponse();
        }
        catch (RuntimeException ex) {
            if (egressBlocked(ex)) {
                // A refusal to send is a security decision, not a provider failure. Retrying on
                // the fallback model would re-send the same payload, and if that model answered
                // from the truncated context the block would surface as a wrong answer instead
                // of an error.
                throw ex;
            }
            throw new ModelCallException(model, ex, elapsedMillis(startedAt));
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        logUsage(model, response, elapsedNanos, requestId, tools);
        String content = response == null || response.getResult() == null
                || response.getResult().getOutput() == null ? null : response.getResult().getOutput().getText();
        long structuredStartedAt = System.nanoTime();
        ChatResponse.ModelAnswer parsed;
        try {
            parsed = this.answerConverter.convert(content);
        }
        catch (RuntimeException ex) {
            logger.warn("Structured model response parse failed requestId={} model={} errorType={} durationMs={}",
                    requestId, model, ex.getClass().getSimpleName(), elapsedMillis(structuredStartedAt));
            parsed = new ChatResponse.ModelAnswer(ChatResponse.Status.ERROR, STRUCTURED_OUTPUT_ERROR,
                    List.of(), List.of(), false, "", "");
        }
        ChatResponse.ModelAnswer sanitized = sanitize(parsed, guardSession);
        String protectedContent = sanitized.toString();
        this.traceLogger.traceFinalModelResponse(requestId, protectedContent, protectedContent);
        return new Result(model, fallbackUsed, sanitized, protectedContent);
    }

    private static boolean egressBlocked(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.ai.querymateai.security.SensitiveEgressFirewall.SensitiveEgressBlockedException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static ChatResponse.ModelAnswer sanitize(ChatResponse.ModelAnswer answer,
            PrivacySession guardSession) {
        if (answer == null) {
            return new ChatResponse.ModelAnswer(ChatResponse.Status.ERROR, STRUCTURED_OUTPUT_ERROR,
                    List.of(), List.of(), false, "", "");
        }
        return new ChatResponse.ModelAnswer(answer.status(), guardSession.protectOutput(answer.answer()),
                answer.columns(),
                answer.rows().stream()
                        .map(row -> protectRow(answer.columns(), row, guardSession))
                        .toList(),
                answer.partialResults(), guardSession.protectOutput(answer.dataNotes()),
                guardSession.protectOutput(answer.followUpQuestion()));
    }

    private static List<String> protectRow(List<String> columns, List<String> row,
            PrivacySession guardSession) {
        java.util.ArrayList<String> protectedRow = new java.util.ArrayList<>(row.size());
        for (int i = 0; i < row.size(); i++) {
            String column = i < columns.size() ? columns.get(i) : "";
            protectedRow.add(guardSession.protectStructuredCell(column, row.get(i)));
        }
        return protectedRow;
    }

    OpenAiChatOptions.Builder chatOptions(String model, ToolCallback[] tools) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(this.properties.execution().temperature())
                .maxCompletionTokens(this.properties.execution().maxCompletionTokens())
                .toolCallbacks(tools)
                .parallelToolCalls(false)
                .timeout(this.properties.execution().requestTimeout())
                .maxRetries(0);
        if (this.properties.execution().responseFormat() == AppProperties.ResponseFormat.JSON_SCHEMA) {
            options.responseFormat(OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(this.answerConverter.getJsonSchema())
                    .strict(Boolean.FALSE)
                    .build());
        }
        return options;
    }

    private String systemPrompt(String systemPrompt) {
        if (this.properties.execution().responseFormat() == AppProperties.ResponseFormat.PROMPT_JSON) {
            return systemPrompt + "\n\n" + this.answerConverter.getFormat();
        }
        return systemPrompt;
    }

    private static void logUsage(String model, org.springframework.ai.chat.model.ChatResponse response,
            long elapsedNanos, String requestId, ToolCallback[] tools) {
        long latencyMs = elapsedNanos / 1_000_000L;
        Usage usage = response != null && response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage == null) {
            logger.info("OpenAI request completed requestId={} model={} tools={} durationMs={} tokens=unavailable",
                    requestId, model, tools.length, latencyMs);
            return;
        }
        logger.info("OpenAI request completed requestId={} model={} tools={} durationMs={} promptTokens={} "
                + "completionTokens={} totalTokens={}", requestId, model, tools.length, latencyMs, usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static void logModelFailure(ModelCallException failure, String requestId) {
        logger.warn("OpenAI request failed requestId={} model={} errorType={} durationMs={}",
                requestId, failure.model(),
                failure.getCause().getClass().getSimpleName(), failure.elapsedMs());
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static ResponseStatusException chatFailure(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private static String userMessageFor(String defaultMessage, ModelCallException failure) {
        Throwable cause = failure == null ? null : failure.getCause();
        while (cause != null) {
            if (cause instanceof RateLimitException) {
                return RATE_LIMIT_ERROR;
            }
            if (cause instanceof OpenAIInvalidDataException) {
                return INVALID_DATA_ERROR;
            }
            cause = cause.getCause();
        }
        return defaultMessage;
    }

    public record Result(String model, boolean fallbackUsed, ChatResponse.ModelAnswer answer,
            String protectedModelOutput) {
    }

    private static final class ModelCallException extends RuntimeException {

        private final String model;

        private final long elapsedMs;

        private ModelCallException(String model, RuntimeException cause, long elapsedMs) {
            super(cause);
            this.model = model;
            this.elapsedMs = elapsedMs;
        }

        private String model() {
            return this.model;
        }

        private long elapsedMs() {
            return this.elapsedMs;
        }
    }
}
