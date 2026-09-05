package com.ai.querymateai.trace;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.ai.querymateai.chat.ChatResponse;
import com.ai.querymateai.config.AppProperties;
/**
 * Single console trace for a local AI request. It is deliberately INFO-level so
 * local developers can read one request flow without changing logger levels.
 */
@Component
public class LocalAiTraceLogger {

    private static final Logger logger = LoggerFactory.getLogger(LocalAiTraceLogger.class);

    private static final Set<String> LOCAL_PROFILES = Set.of("local", "dev");

    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;}]+");

    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(openai_api_key|api[-_]?key|db[-_]?password|password|token|secret|cookie|vault[-_]?token)"
                    + "(\\s*[:=]\\s*[\"']?)[^\"'\\s,;}]+");

    private static final Pattern CONNECTION_PASSWORD = Pattern.compile("(?i)(password=)[^;\\s]+");

    private final AppProperties properties;

    private final Set<String> activeProfiles;

    public LocalAiTraceLogger(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.activeProfiles = Set.of(environment.getActiveProfiles());
    }

    @PostConstruct
    void logStartupWarning() {
        if (!enabled()) {
            return;
        }
        if (includeSensitiveValues()) {
            logger.warn("UNSAFE LOCAL AI TRACE ENABLED: app.ai.trace.enabled=true and "
                    + "app.ai.trace.include-sensitive-values=true. Raw business PII may appear in logs. "
                    + "Secrets and credentials are still redacted.");
        }
        else {
            logger.info("Local AI trace enabled with sensitive values hidden. Set "
                    + "app.ai.trace.include-sensitive-values=true only in a local/dev profile to show raw business PII.");
        }
    }

    public boolean enabled() {
        return this.properties.ai().trace().enabled();
    }

    public boolean includeSensitiveValues() {
        return enabled() && this.properties.ai().trace().includeSensitiveValues()
                && this.activeProfiles.stream().anyMatch(LOCAL_PROFILES::contains);
    }

    public void traceLlmRequest(String requestId, String provider, String model, String systemPrompt,
            List<Message> history, String userMessage, ToolCallback[] tools) {
        String toolNames = tools == null ? "[]" : List.of(tools).stream()
                .map(tool -> tool.getToolDefinition().name()).collect(Collectors.joining(", ", "[", "]"));
        String body = """
                Provider: %s
                Model: %s

                System prompt:
                %s

                Conversation context:
                %s

                User message:
                %s

                Available tools:
                %s
                """.formatted(provider, model, systemPrompt,
                history == null ? List.of() : history.stream().map(Message::getText).toList(),
                userMessage, toolNames);
        trace(requestId, "LLM REQUEST - TO MODEL", body);
    }

    public void traceModelToolRequest(String requestId, String toolName, String arguments) {
        trace(requestId, "DAB REQUEST - FROM MODEL", """
                Tool:
                %s

                Arguments from model:
                %s
                """.formatted(toolName, arguments));
    }

    public void traceToolRequestAfterDetokenization(String requestId, String before, String after,
            int resolvedValues) {
        trace(requestId, "DAB REQUEST - BEFORE EXECUTION", """
                Protected arguments from model:
                %s

                Arguments sent to DAB:
                %s

                Resolved protected values:
                %d
                """.formatted(before, includeSensitiveValues() ? after : before, resolvedValues));
    }

    public void traceRawToolResult(String requestId, String toolName, String entity, int rows, long durationMs,
            String rawResult) {
        trace(requestId, "DAB RESPONSE - FROM DAB", """
                Tool: %s
                Entity: %s
                Rows: %s
                DurationMs: %d

                %s
                """.formatted(toolName, entity, rows >= 0 ? Integer.toString(rows) : "unknown", durationMs,
                includeSensitiveValues() ? rawResult : "<raw DAB result hidden>"));
    }

    public void traceProtectedToolResult(String requestId, String protectedResult) {
        trace(requestId, "DAB RESPONSE - TO MODEL", protectedResult);
    }

    public void traceFinalModelResponse(String requestId, String rawModelResponse, String protectedModelResponse) {
        trace(requestId, "LLM RESPONSE - FROM MODEL",
                includeSensitiveValues() ? rawModelResponse : protectedModelResponse);
    }

    public void traceFinalUiResponse(String requestId, ChatResponse protectedResponse, ChatResponse uiResponse) {
        trace(requestId, "UI RESPONSE - TO BROWSER",
                String.valueOf(includeSensitiveValues() ? uiResponse : protectedResponse));
    }

    private void trace(String requestId, String title, String body) {
        if (!enabled()) {
            return;
        }
        logger.info("""
                ============================================================
                [AI TRACE] requestId={} {}
                ============================================================
                {}""", requestId, title, truncate(redactSecrets(body)));
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        int max = this.properties.ai().trace().maxPayloadChars();
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n... <truncated, originalLength=" + value.length() + ">";
    }

    private static String redactSecrets(String value) {
        if (value == null) {
            return "";
        }
        String redacted = AUTH_HEADER.matcher(value).replaceAll("$1$2[REDACTED_SECRET]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED_SECRET]");
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1$2[REDACTED_SECRET]");
        return CONNECTION_PASSWORD.matcher(redacted).replaceAll("$1[REDACTED_SECRET]");
    }

}
