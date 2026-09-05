package com.ai.querymateai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import com.ai.querymateai.security.SensitiveEgressFirewall;

class AiConfigurationTests {

    @Test
    void chatMemoryBeanUsesConfiguredWindow() {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, java.time.Duration.ofSeconds(120),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(3),
                new AppProperties.Security(null, null, null),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(false, false, 20_000)),
                java.util.List.of());

        ChatMemory chatMemory = new AiConfiguration().chatMemory(properties);

        assertThat(chatMemory).isNotNull();
    }

    @Test
    void egressFirewallIsAttachedToTheOpenAiHttpClient() {
        AiConfiguration configuration = new AiConfiguration();
        SensitiveEgressFirewall firewall = configuration.sensitiveEgressFirewall();
        SpringAiOpenAiHttpClient.Builder builder = SpringAiOpenAiHttpClient.builder();

        configuration.sensitiveEgressFirewallCustomizer(firewall).customize(builder);

        try (SpringAiOpenAiHttpClient client = builder.build()) {
            assertThat(client.getOkHttpClient().interceptors()).contains(firewall);
        }
    }

    @Test
    void dataPolicySafeColumnsBindFromConfiguration() {
        org.springframework.boot.context.properties.bind.Binder binder =
                new org.springframework.boot.context.properties.bind.Binder(
                        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                                java.util.Map.of("app.security.data-policy.safe-columns[0]", "City",
                                        "app.security.data-policy.safe-columns[1]", "LoyaltyTier")));

        AppProperties.Security bound = binder.bind("app.security", AppProperties.Security.class).get();

        assertThat(bound.dataPolicy().safeColumns()).containsExactly("city", "loyaltytier");
    }

    @Test
    void executionDefaultsFavorPromptJsonWithoutPrimaryRetry() {
        AppProperties.Execution execution = new AppProperties.Execution(true, false, 1200, 0.1, null, null);

        assertThat(execution.primaryRetryEnabled()).isFalse();
        assertThat(execution.requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(execution.responseFormat()).isEqualTo(AppProperties.ResponseFormat.PROMPT_JSON);
    }
}
