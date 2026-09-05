package com.ai.querymateai.chat;

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

import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.mcp.McpToolCatalog;
import com.ai.querymateai.security.SensitiveDataGuard;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.json.JsonMapper;

class ChatCoordinatorTests {

    private static final String EMAIL = "jane.doe@example.com";

    private static final String PHONE = "415-555-0101";

    private static final String NAME = "John Smith";

    private static final String PROTECTED_PATTERN =
            "\\b(?:CustomerName|Email|Phone|SensitiveValue)Protected#\\d+\\b";

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

        assertThat(result.message()).doesNotContain(EMAIL, PHONE)
                .contains("jan***@example.com", "415***01");
        assertThat(result.rows().getFirst().get(1)).isEqualTo("jan***@example.com");
        assertThat(receivedPrompt.get().getContents()).doesNotContain(EMAIL, PHONE);
        assertThat(fixture.memory().get("privacy-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(EMAIL, PHONE));
    }

    @Test
    void uiResponseShowsNamesAndMasksContactsWhileMemoryKeepsTokens() {
        ChatModel model = prompt -> {
            return response("""
                    {"status":"ANSWER","answer":"Customer John Smith is a Gold-tier customer.",
                    "columns":["CustomerId","CustomerNameProtected","Email"],"rows":[["42","John Smith","jane.doe@example.com"]],
                    "partialResults":false,"dataNotes":"Sensitive fields are protected.","followUpQuestion":""}
                    """);
        };
        Fixture fixture = fixture(model, false);

        ChatResponse result = fixture.coordinator().chat(
                "Find customer named John Smith with jane.doe@example.com", "local-display-test");

        assertThat(result.message()).contains(NAME).doesNotContain(EMAIL);
        assertThat(result.rows().getFirst().get(1)).isEqualTo(NAME);
        assertThat(result.rows().getFirst().get(2)).isEqualTo("jan***@example.com");
        assertThat(fixture.memory().get("local-display-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(NAME, EMAIL)
                        .doesNotContain("jan***@example.com")
                        .containsPattern(PROTECTED_PATTERN));
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
                new AppProperties.Security(),
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
