package com.example.sqlmcpchatopenrouter.trace;

import java.util.List;
import java.util.Map;
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

import com.example.sqlmcpchatopenrouter.chat.ChatResponse;
import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.security.SensitiveTokenStore;

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

    public void traceRawUserRequest(String requestId, String rawMessage) {
        trace(requestId, 1, "RAW USER REQUEST", includeSensitiveValues() ? rawMessage : "<raw user request hidden>");
    }

    public void tracePiiProtection(String requestId, String before, String detected, String tokenMap, String after) {
        String body = """
                Before:
                %s

                Detected:
                %s

                After:
                %s
                """.formatted(includeSensitiveValues() ? before : "<raw input hidden>", detected, after);
        if (includeSensitiveValues()) {
            body += "\nToken map:\n" + tokenMap;
        }
        trace(requestId, 2, "PII PROTECTION", body);
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
        trace(requestId, 3, "LLM INPUT", body);
    }

    public void traceModelToolRequest(String requestId, String toolName, String arguments) {
        trace(requestId, 4, "MODEL TOOL REQUEST", """
                Tool:
                %s

                Arguments from model:
                %s
                """.formatted(toolName, arguments));
    }

    public void traceToolRequestAfterDetokenization(String requestId, String before, String after,
            String tokenResolution) {
        trace(requestId, 5, "TOOL REQUEST AFTER DETOKENIZATION", """
                Before:
                %s

                After:
                %s

                Resolved tokens:
                %s

                Actual SQL:
                Not available from Spring application. Logged MCP/DAB tool arguments instead.
                """.formatted(before, includeSensitiveValues() ? after : before, tokenResolution));
    }

    public void traceRawToolResult(String requestId, String toolName, String entity, int rows, long durationMs,
            String rawResult) {
        trace(requestId, 6, "RAW DAB RESULT", """
                Tool: %s
                Entity: %s
                Rows: %s
                DurationMs: %d

                %s
                """.formatted(toolName, entity, rows >= 0 ? Integer.toString(rows) : "unknown", durationMs,
                includeSensitiveValues() ? rawResult : "<raw DAB result hidden>"));
    }

    public void traceProtectedToolResult(String requestId, String protectedResult) {
        trace(requestId, 7, "PROTECTED DAB RESULT", protectedResult);
    }

    public void traceFinalModelResponse(String requestId, String rawModelResponse, String protectedModelResponse) {
        trace(requestId, 8, "FINAL MODEL RESPONSE",
                includeSensitiveValues() ? rawModelResponse : protectedModelResponse);
    }

    public void traceFinalUiResponse(String requestId, ChatResponse protectedResponse, ChatResponse uiResponse) {
        trace(requestId, 9, "FINAL UI RESPONSE",
                String.valueOf(includeSensitiveValues() ? uiResponse : protectedResponse));
    }

    public String describeNewTokens(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        Map<String, String> values = newTokenSnapshot(tokens, existingTokens);
        if (values.isEmpty()) {
            return "none";
        }
        if (includeSensitiveValues()) {
            return values.entrySet().stream()
                    .map(entry -> entry.getValue() + " -> " + entry.getKey())
                    .collect(Collectors.joining("\n"));
        }
        return "entities=" + values.size() + " tokenTypes=" + tokens.prefixes();
    }

    public String describeTokenResolution(String protectedText, SensitiveTokenStore tokens) {
        Map<String, String> matches = tokens.snapshot().entrySet().stream()
                .filter(entry -> protectedText != null && protectedText.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, java.util.LinkedHashMap::new));
        if (matches.isEmpty()) {
            return "none";
        }
        if (includeSensitiveValues()) {
            return matches.entrySet().stream()
                    .map(entry -> entry.getKey() + " -> " + entry.getValue())
                    .collect(Collectors.joining("\n"));
        }
        return "resolvedTokens=" + matches.size();
    }

    private void trace(String requestId, int step, String title, String body) {
        if (!enabled()) {
            return;
        }
        logger.info("""
                ============================================================
                [AI TRACE] requestId={} STEP {} - {}
                ============================================================
                {}""", requestId, step, title, truncate(redactSecrets(body)));
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

    private static Map<String, String> newTokenSnapshot(SensitiveTokenStore tokens,
            Map<String, String> existingTokens) {
        return tokens.snapshot().entrySet().stream()
                .filter(entry -> !existingTokens.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, java.util.LinkedHashMap::new));
    }
}
