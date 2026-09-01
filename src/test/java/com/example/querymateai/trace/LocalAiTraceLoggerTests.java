package com.example.querymateai.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import com.example.querymateai.config.AppProperties;

@ExtendWith(OutputCaptureExtension.class)
class LocalAiTraceLoggerTests {

    @Test
    void disabledTraceDoesNotLogPayload(CapturedOutput output) {
        LocalAiTraceLogger traceLogger = new LocalAiTraceLogger(properties(false, false), new MockEnvironment());

        traceLogger.traceRawUserRequest("abc123", "Show Jane Doe");

        assertThat(output).doesNotContain("AI TRACE", "Jane Doe");
    }

    @Test
    void traceWithoutSensitiveValuesHidesRawUserRequest(CapturedOutput output) {
        LocalAiTraceLogger traceLogger = new LocalAiTraceLogger(properties(true, false), new MockEnvironment());

        traceLogger.traceRawUserRequest("abc123", "Show Jane Doe");

        assertThat(output).contains("AI TRACE", "<raw user request hidden>").doesNotContain("Show Jane Doe");
    }

    @Test
    void localSensitiveTraceLogsBusinessValuesButRedactsSecrets(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalAiTraceLogger traceLogger = new LocalAiTraceLogger(properties(true, true), environment);

        traceLogger.traceRawUserRequest("abc123",
                "Show Jane Doe Authorization: Bearer abc123 OPENAI_API_KEY=sk-test password=secret");

        assertThat(output).contains("Show Jane Doe", "[REDACTED_SECRET]")
                .doesNotContain("sk-test", "password=secret", "Bearer abc123");
    }

    private static AppProperties properties(boolean traceEnabled, boolean includeSensitiveValues) {
        return new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, Duration.ofSeconds(10),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(20),
                new AppProperties.Security("unit-test-secret"),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(traceEnabled, includeSensitiveValues, 20_000)),
                List.of());
    }
}
