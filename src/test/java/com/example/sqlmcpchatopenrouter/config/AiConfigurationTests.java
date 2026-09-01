package com.example.sqlmcpchatopenrouter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

class AiConfigurationTests {

    @Test
    void chatMemoryBeanUsesConfiguredWindow() {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, java.time.Duration.ofSeconds(120),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(3),
                new AppProperties.Security("secret"),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(false, false, 20_000)),
                java.util.List.of());

        ChatMemory chatMemory = new AiConfiguration().chatMemory(properties);

        assertThat(chatMemory).isNotNull();
    }

    @Test
    void executionDefaultsFavorPromptJsonWithoutPrimaryRetry() {
        AppProperties.Execution execution = new AppProperties.Execution(true, false, 1200, 0.1, null, null);

        assertThat(execution.primaryRetryEnabled()).isFalse();
        assertThat(execution.requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(execution.responseFormat()).isEqualTo(AppProperties.ResponseFormat.PROMPT_JSON);
    }
}
