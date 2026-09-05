package com.ai.querymateai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SensitiveLoggingPolicyTests {

    @Test
    void sensitiveLoggingDefaultsToFalse() {
        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(false, false), new MockEnvironment());

        assertThat(policy.sensitiveLoggingEnabled()).isFalse();
    }

    @Test
    void sensitiveLoggingRequiresLocalOrDevProfile() {
        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(true, true), new MockEnvironment());

        assertThat(policy.sensitiveLoggingEnabled()).isFalse();
    }

    @Test
    void sensitiveLoggingCanBeEnabledForLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(true, true), environment);

        assertThat(policy.sensitiveLoggingEnabled()).isTrue();
    }

    @Test
    void sensitiveLoggingRequiresTraceEnabledAndIncludeSensitiveValues() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(true, false), environment);

        assertThat(policy.sensitiveLoggingEnabled()).isFalse();
    }

    private static AppProperties properties(boolean traceEnabled, boolean includeSensitiveValues) {
        return new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, Duration.ofSeconds(10),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(20),
                new AppProperties.Security(),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(traceEnabled, includeSensitiveValues, 20_000)),
                List.of());
    }
}
