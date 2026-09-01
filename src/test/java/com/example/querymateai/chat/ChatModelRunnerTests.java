package com.example.querymateai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.server.ResponseStatusException;

import com.example.querymateai.config.AppProperties;
import com.example.querymateai.security.SensitiveDataGuard;
import com.example.querymateai.security.SensitiveRequestContext;
import com.example.querymateai.trace.LocalAiTraceLogger;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.RateLimitException;

import tools.jackson.databind.json.JsonMapper;

class ChatModelRunnerTests {

    private static final String VALID_ANSWER = """
            {"status":"ANSWER","answer":"Done.","columns":[],"rows":[],
            "partialResults":false,"dataNotes":"","followUpQuestion":""}
            """;

    @Test
    void primaryFailureFallsBackImmediatelyWhenPrimaryRetryIsDisabled() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA, prompt -> {
            String model = model(prompt);
            calls.add(model);
            if (model.equals("primary")) {
                throw new IllegalStateException("provider failure");
            }
            return response(VALID_ANSWER);
        });

        ChatModelRunner.Result result = fixture.run();

        assertThat(calls).containsExactly("primary", "fallback");
        assertThat(result.model()).isEqualTo("fallback");
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    void primaryRetryHappensOnlyWhenPrimaryRetryIsEnabled() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(true, AppProperties.ResponseFormat.JSON_SCHEMA, prompt -> {
            String model = model(prompt);
            calls.add(model);
            if (calls.size() == 1) {
                throw new IllegalStateException("transient provider failure");
            }
            return response(VALID_ANSWER);
        });

        ChatModelRunner.Result result = fixture.run();

        assertThat(calls).containsExactly("primary", "primary");
        assertThat(result.model()).isEqualTo("primary");
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    void structuredOutputParseFailureReturnsSafeErrorResponse() {
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA,
                prompt -> response("not-json jane.doe@example.com 415-555-0101"));

        ChatModelRunner.Result result = fixture.run();

        assertThat(result.answer().status()).isEqualTo(ChatResponse.Status.ERROR);
        assertThat(result.answer().answer()).contains("couldn't safely interpret")
                .doesNotContain("jane.doe@example.com", "415-555-0101");
        assertThat(result.answer().columns()).isEmpty();
        assertThat(result.answer().rows()).isEmpty();
    }

    @Test
    void promptJsonOmitsProviderResponseFormatAndStillParsesTypedResponse() {
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.PROMPT_JSON, prompt -> {
            assertThat(prompt.getSystemMessage().getText()).contains("JSON Schema instance");
            return response(VALID_ANSWER);
        });

        ChatModelRunner.Result result = fixture.run();

        OpenAiChatOptions options = fixture.runner().chatOptions("primary", new ToolCallback[0]).build();
        assertThat(options.getResponseFormat()).isNull();
        assertThat(result.answer().status()).isEqualTo(ChatResponse.Status.ANSWER);
    }

    @Test
    void jsonSchemaConfiguresProviderResponseFormat() {
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA,
                prompt -> response(VALID_ANSWER));

        OpenAiChatOptions options = fixture.runner().chatOptions("primary", new ToolCallback[0]).build();

        assertThat(options.getResponseFormat()).isNotNull();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
        assertThat(options.getResponseFormat().getJsonSchema()).isNotBlank();
    }

    @Test
    void rateLimitFailureReturnsSafeUserMessage() {
        RateLimitException failure = RateLimitException.builder().headers(Headers.builder().build()).build();
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.PROMPT_JSON, prompt -> {
            throw failure;
        });

        assertThatThrownBy(fixture::run)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "The model provider is rate-limited. Please try again shortly or switch models.")
                .hasMessageNotContaining(failure.getMessage());
    }

    @Test
    void invalidDataFailureReturnsSafeUserMessage() {
        OpenAIInvalidDataException failure = new OpenAIInvalidDataException("provider payload details");
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA, prompt -> {
            throw failure;
        });

        assertThatThrownBy(fixture::run)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("The selected model returned an unsupported response format. "
                        + "Try another model or use prompt_json mode.")
                .hasMessageNotContaining("provider payload details");
    }

    @Test
    void structuredResponseKeepsCustomerIdSeparateFromExplicitlyRequestedNameToken() {
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA, prompt -> response("""
                {"status":"ANSWER","answer":"Found one customer.",
                "columns":["CustomerId","CustomerNameToken","City","LoyaltyTier"],
                "rows":[["42","CU_ab12cd","Austin","Gold"]],
                "partialResults":false,"dataNotes":"","followUpQuestion":""}
                """));

        ChatResponse.ModelAnswer answer = fixture.run().answer();

        assertThat(answer.columns())
                .containsExactly("CustomerId", "CustomerNameToken", "City", "LoyaltyTier");
        assertThat(answer.rows()).containsExactly(List.of("42", "CU_ab12cd", "Austin", "Gold"));
        assertThat(answer.rows().getFirst().getFirst()).as("CustomerId must remain the stable database ID")
                .doesNotStartWith("CU_");
        assertThat(answer.rows().getFirst().get(1)).as("explicitly requested name may be pseudonymized")
                .startsWith("CU_");
    }

    private static Fixture fixture(boolean primaryRetryEnabled, AppProperties.ResponseFormat responseFormat,
            Function<Prompt, org.springframework.ai.chat.model.ChatResponse> responder) {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, primaryRetryEnabled, 1200, 0.1, Duration.ofSeconds(10),
                        responseFormat),
                new AppProperties.Memory(20),
                new AppProperties.Security("unit-test-secret"), new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(false, false, 20_000)), List.of());
        LocalAiTraceLogger traceLogger = new LocalAiTraceLogger(properties,
                new org.springframework.mock.env.MockEnvironment());
        SensitiveDataGuard guard = new SensitiveDataGuard(properties, JsonMapper.builder().build(), traceLogger);
        ChatModel model = responder::apply;
        ChatModelRunner runner = new ChatModelRunner(ChatClient.builder(model), properties, traceLogger);
        return new Fixture(runner, guard.newSession());
    }

    private static String model(Prompt prompt) {
        return prompt.getOptions().getModel();
    }

    private static org.springframework.ai.chat.model.ChatResponse response(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(content))));
    }

    private record Fixture(ChatModelRunner runner, SensitiveRequestContext guardSession) {

        private ChatModelRunner.Result run() {
            return this.runner.run("question", "system", List.of(), new ToolCallback[0], this.guardSession,
                    ProgressSink.none(), "test1234");
        }
    }
}
