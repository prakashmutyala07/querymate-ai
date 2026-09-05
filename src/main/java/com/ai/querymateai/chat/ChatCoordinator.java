package com.ai.querymateai.chat;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.ai.querymateai.mcp.McpToolCatalog;
import com.ai.querymateai.security.SensitiveDataGuard;
import com.ai.querymateai.security.SensitiveRequestContext;
import com.ai.querymateai.trace.LocalAiTraceLogger;

/** Coordinates a chat turn; specialized collaborators own prompts, privacy, tools, and model policy. */
@Service
public class ChatCoordinator implements ChatOperations {

    private static final Logger logger = LoggerFactory.getLogger(ChatCoordinator.class);

    private final McpToolCatalog mcpToolCatalog;

    private final ChatMemory chatMemory;

    private final SensitiveDataGuard sensitiveDataGuard;

    private final PromptProvider promptProvider;

    private final ChatModelRunner modelRunner;

    private final LocalAiTraceLogger traceLogger;

    public ChatCoordinator(McpToolCatalog mcpToolCatalog, ChatMemory chatMemory,
            SensitiveDataGuard sensitiveDataGuard, PromptProvider promptProvider, ChatModelRunner modelRunner,
            LocalAiTraceLogger traceLogger) {
        this.mcpToolCatalog = mcpToolCatalog;
        this.chatMemory = chatMemory;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.promptProvider = promptProvider;
        this.modelRunner = modelRunner;
        this.traceLogger = traceLogger;
    }

    @Override
    public ChatResponse chat(String message, String conversationId) {
        return chat(message, conversationId, ProgressSink.none());
    }

    @Override
    public ChatResponse chat(String message, String conversationId, ProgressSink progressSink) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId
                : UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long requestStartedAt = System.nanoTime();

        progressSink.progress("prepare", "Preparing a safe database request\u2026");
        SensitiveRequestContext guardSession =
                this.sensitiveDataGuard.newSession(requestId, step -> progressSink.progress("tool", step));
        String protectedMessage = guardSession.protectInput(message);
        // Everything this thread sends to the model provider from here on is verified against
        // this turn's vault; unbound in the finally block below.
        guardSession.bindToEgress();
        try {
            ToolCallback[] tools = guardSession.wrap(this.mcpToolCatalog.toolCallbacksOrEmpty());
            progressSink.progress("mcp", "Connected to DAB (" + tools.length + " tools).");
            List<Message> history = this.chatMemory.get(resolvedConversationId);
            ChatModelRunner.Result result = this.modelRunner.run(protectedMessage, this.promptProvider.systemPrompt(),
                    history, tools, guardSession, progressSink, requestId);
            long structuredStartedAt = System.nanoTime();
            ChatResponse protectedResponse = ChatResponse.from(resolvedConversationId, result.model(), result.fallbackUsed(),
                    result.answer(), guardSession.toolInvocations() > 0, guardSession.resolvedTotalCount());
            ChatResponse uiResponse = guardSession.toUiResponse(protectedResponse);
            storeSanitizedTurn(resolvedConversationId, protectedMessage, protectedResponse);
            logger.info("Chat response structured requestId={} conversationId={} status={} rows={} "
                    + "memorySanitized=true durationMs={}", requestId, resolvedConversationId,
                    protectedResponse.status(), protectedResponse.rows().size(), elapsedMillis(structuredStartedAt));
            this.traceLogger.traceFinalUiResponse(requestId, protectedResponse, uiResponse);
            return uiResponse;
        }
        catch (RuntimeException ex) {
            logger.error("Chat request failed requestId={} conversationId={} errorType={} durationMs={}",
                    requestId, resolvedConversationId, ex.getClass().getSimpleName(),
                    elapsedMillis(requestStartedAt));
            throw ex;
        }
        finally {
            guardSession.unbindFromEgress();
            progressSink.progress("done", "Composing answer\u2026");
        }
    }

    @Override
    public void clearMemory(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            this.chatMemory.clear(conversationId);
        }
    }

    @Override
    public List<McpToolCatalog.ToolSummary> tools() {
        return this.mcpToolCatalog.tools();
    }

    private void storeSanitizedTurn(String conversationId, String protectedMessage, ChatResponse response) {
        this.chatMemory.add(conversationId, new UserMessage(protectedMessage));
        this.chatMemory.add(conversationId, new AssistantMessage(memoryContent(response)));
    }

    private static String memoryContent(ChatResponse response) {
        return "status=" + response.status() + "; answer=" + response.message()
                + "; columns=" + response.columns() + "; rows=" + response.rows()
                + "; dataNotes=" + response.dataNotes() + "; followUpQuestion=" + response.followUpQuestion();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
