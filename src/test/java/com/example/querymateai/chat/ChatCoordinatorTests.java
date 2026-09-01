package com.example.querymateai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;

import com.example.querymateai.config.AppProperties;
import com.example.querymateai.mcp.McpToolCatalog;
import com.example.querymateai.security.SensitiveDataGuard;
import com.example.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.json.JsonMapper;

class ChatCoordinatorTests {

    private static final String EMAIL = "jane.doe@example.com";

    private static final String PHONE = "415-555-0101";

    private static final String NAME = "Jane Doe";

    @Test
    void rawInputAndModelOutputNeverReachModelOrMemoryInNormalMode() {
        AtomicReference<Prompt> receivedPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            receivedPrompt.set(prompt);
            return response("""
                    {"status":"ANSWER","answer":"Contact jane.doe@example.com or 415-555-0101.",
                    "columns":["CustomerId","Email"],"rows":[["42","jane.doe@example.com"]],
                    "partialResults":false,"dataNotes":"Phone 415-555-0101","followUpQuestion":""}
                    """);
        };
        Fixture fixture = fixture(model, false);

        ChatResponse result = fixture.coordinator().chat(
                "Find jane.doe@example.com or call 415-555-0101", "privacy-test");

        assertThat(result.message()).contains(EMAIL, PHONE);
        assertThat(result.rows().getFirst().get(1)).isEqualTo(EMAIL);
        assertThat(receivedPrompt.get().getContents()).doesNotContain(EMAIL, PHONE);
        assertThat(fixture.memory().get("privacy-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(EMAIL, PHONE));
    }

    @Test
    void uiResponseRevealsSensitiveValuesButKeepsMemorySanitized() {
        ChatModel model = prompt -> {
            String nameToken = prompt.getContents().replaceAll("(?s).*?(CU_[0-9a-f]{6}).*", "$1");
            String emailToken = prompt.getContents().replaceAll("(?s).*?(EM_[0-9a-f]{6}).*", "$1");
            return response("""
                    {"status":"ANSWER","answer":"Customer %s is a Gold-tier customer.",
                    "columns":["CustomerId","CustomerNameToken","EmailToken"],"rows":[["42","%s","%s"]],
                    "partialResults":false,"dataNotes":"Sensitive fields are tokenized.","followUpQuestion":""}
                    """.formatted(nameToken, nameToken, emailToken));
        };
        Fixture fixture = fixture(model, false);

        ChatResponse result = fixture.coordinator().chat(
                "Find customer named Jane Doe with jane.doe@example.com", "local-display-test");

        assertThat(result.message()).contains(NAME).doesNotContain("CU_d18af0");
        assertThat(result.rows().getFirst()).contains(NAME, EMAIL);
        assertThat(fixture.memory().get("local-display-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(NAME, EMAIL));
    }

    @Test
    void malformedStructuredOutputReturnsAndStoresSafeError() {
        Fixture fixture = fixture(prompt -> response("not-json jane.doe@example.com 415-555-0101"), false);

        ChatResponse result = fixture.coordinator().chat("Show orders", "parse-test");

        assertThat(result.status()).isEqualTo(ChatResponse.Status.ERROR);
        assertThat(result.message()).contains("couldn't safely interpret").doesNotContain(EMAIL, PHONE);
        assertThat(fixture.memory().get("parse-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(EMAIL, PHONE));
    }

    private static Fixture fixture(ChatModel model, boolean localSensitiveMode) {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, Duration.ofSeconds(10),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(20),
                new AppProperties.Security("unit-test-secret"),
                new AppProperties.Logging(localSensitiveMode),
                new AppProperties.Ai(new AppProperties.Trace(localSensitiveMode, localSensitiveMode, 20_000)),
                List.of(new AppProperties.SensitiveField("Customer", "Email", "EM"),
                        new AppProperties.SensitiveField("Customer", "Phone", "PH"),
                        new AppProperties.SensitiveField("Customer", "FullName", "CU")));
        MockEnvironment environment = new MockEnvironment();
        if (localSensitiveMode) {
            environment.setActiveProfiles("local");
        }
        LocalAiTraceLogger traceLogger = new LocalAiTraceLogger(properties, environment);
        SensitiveDataGuard guard = new SensitiveDataGuard(properties, JsonMapper.builder().build(), traceLogger);
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        PromptProvider prompts = new PromptProvider(new ByteArrayResource(
                "Date __CURRENT_DATE__, zone __TIME_ZONE__.".getBytes(StandardCharsets.UTF_8)));
        ChatModelRunner runner = new ChatModelRunner(ChatClient.builder(model), properties, traceLogger);
        ChatCoordinator coordinator = new ChatCoordinator(new EmptyToolCatalog(), memory, guard, prompts, runner,
                traceLogger);
        return new Fixture(coordinator, memory);
    }

    private static org.springframework.ai.chat.model.ChatResponse response(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(content))));
    }

    private record Fixture(ChatCoordinator coordinator, ChatMemory memory) {
    }

    private static final class EmptyToolCatalog extends McpToolCatalog {

        private EmptyToolCatalog() {
            super(null);
        }

        @Override
        public ToolCallback[] toolCallbacksOrEmpty() {
            return new ToolCallback[0];
        }

        @Override
        public List<ToolSummary> tools() {
            return List.of();
        }
    }
}
