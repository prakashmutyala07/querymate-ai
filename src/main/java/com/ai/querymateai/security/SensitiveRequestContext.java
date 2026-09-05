package com.ai.querymateai.security;

import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import com.ai.querymateai.chat.ChatResponse;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.ObjectMapper;

/**
 * Per-request sensitive-data state. One instance per chat turn; discarded when the turn ends.
 */
public final class SensitiveRequestContext {

    private final java.util.concurrent.atomic.AtomicInteger toolInvocations =
            new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.function.Consumer<String> onStep;

    private final String requestId;

    private final ObjectMapper objectMapper;

    private final LocalAiTraceLogger traceLogger;

    private final JavaPiiProtector piiProtector;

    private final SensitivePayloadProtector payloadProtector;

    private final Set<String> protectedFields;

    SensitiveRequestContext(String requestId, java.util.function.Consumer<String> onStep,
            ObjectMapper objectMapper, LocalAiTraceLogger traceLogger, JavaPiiProtector piiProtector,
            SensitivePayloadProtector payloadProtector, Set<String> protectedFields) {
        this.requestId = requestId;
        this.onStep = onStep;
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.piiProtector = piiProtector;
        this.payloadProtector = payloadProtector;
        this.protectedFields = protectedFields;
    }

    /** Decorates each MCP tool so its result is protected and audited before the model sees it. */
    public ToolCallback[] wrap(ToolCallback[] delegates) {
        ToolCallback[] wrapped = new ToolCallback[delegates.length];
        for (int i = 0; i < delegates.length; i++) {
            wrapped[i] = new SecureMcpToolCallback(delegates[i], this, this.objectMapper,
                    this.payloadProtector, this.traceLogger);
        }
        return wrapped;
    }

    /** Removes recognizable PII before the user message reaches memory or the model. */
    public String protectInput(String text) {
        return this.piiProtector.protect(text);
    }

    /** Final defense for provider-generated supported PII. */
    public String protectOutput(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return this.piiProtector.protect(text);
    }

    /** How many tool calls actually executed this turn. Ground truth for usedDatabaseTools. */
    public int toolInvocations() {
        return this.toolInvocations.get();
    }

    String restoreProtectedValues(String text) {
        return this.piiProtector.restoreProtectedValues(text);
    }

    void recordToolInvocation() {
        this.toolInvocations.incrementAndGet();
    }

    void onStep(String step) {
        this.onStep.accept(step);
    }

    String requestId() {
        return this.requestId;
    }

    LocalAiTraceLogger traceLogger() {
        return this.traceLogger;
    }

    int resolvedProtectedValueCount(String before, String after) {
        return this.piiProtector.resolvedProtectedValueCount(before, after);
    }

    int protectedValueCount(String text) {
        return this.piiProtector.protectedValueCount(text);
    }

    public String protectStructuredCell(String column, String value) {
        if (!StringUtils.hasText(value) || this.piiProtector.protectedValueCount(value) > 0) {
            return value;
        }
        String normalized = column == null ? "" : column.toLowerCase();
        if (this.protectedFields.contains(normalized)
                || normalized.contains("email") || normalized.contains("phone")
                || normalized.contains("fullname") || normalized.contains("nameprotected")
                || normalized.endsWith("name")) {
            return this.piiProtector.protectKnownSensitiveValue(value, column);
        }
        return this.piiProtector.protectContactDetails(value);
    }

    public ChatResponse toUiResponse(ChatResponse response) {
        java.util.List<String> columns = response.columns().stream()
                .map(this.piiProtector::displayColumnName).toList();
        java.util.List<java.util.List<String>> rows = response.rows().stream()
                .map(row -> row.stream().map(this.piiProtector::displayProtectedValues).toList())
                .toList();
        return new ChatResponse(response.conversationId(), response.model(), response.fallbackUsed(),
                response.status(), displayMessage(response.message(), columns, rows), columns, rows,
                response.usedDatabaseTools(), response.partialResults(),
                displayMessage(response.dataNotes(), columns, rows),
                displayMessage(response.followUpQuestion(), columns, rows));
    }

    private String displayMessage(String message, java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows) {
        String displayed = this.piiProtector.displayProtectedValues(message);
        if (!StringUtils.hasText(displayed) || !displayed.contains("CustomerNameProtected#")) {
            return displayed;
        }
        int fullNameIndex = columns.indexOf("FullName");
        if (fullNameIndex < 0 || rows.size() != 1 || rows.getFirst().size() <= fullNameIndex) {
            return displayed;
        }
        return displayed.replaceAll("\\bCustomerNameProtected#\\d+\\b",
                java.util.regex.Matcher.quoteReplacement(rows.getFirst().get(fullNameIndex)));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

}
