package com.ai.querymateai.security;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import com.ai.querymateai.chat.ChatResponse;
import com.ai.querymateai.mcp.ToolCallIntent;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.ObjectMapper;

/**
 * Per-request privacy state. One instance per chat turn; closed when the turn ends.
 */
public final class PrivacySession implements AutoCloseable {

    private final java.util.concurrent.atomic.AtomicInteger toolInvocations =
            new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.Map<String, Long> totalsByScope = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.Map<String, Integer> rowsByScope = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.function.Consumer<String> onStep;

    private final String requestId;

    private final ObjectMapper objectMapper;

    private final LocalAiTraceLogger traceLogger;

    private final PiiDetector detector;

    private final RequestTokenVault vault;

    private final DataDisclosurePolicy policy;

    private final ToolResultProcessor toolResultProcessor;

    private final UiDisclosureService uiDisclosureService;

    PrivacySession(String requestId, java.util.function.Consumer<String> onStep, ObjectMapper objectMapper,
            LocalAiTraceLogger traceLogger, PiiDetector detector, DataDisclosurePolicy policy) {
        this.requestId = requestId;
        this.onStep = onStep;
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.detector = detector;
        this.vault = new RequestTokenVault();
        this.policy = policy;
        this.toolResultProcessor = new ToolResultProcessor(objectMapper, policy, this.vault);
        this.uiDisclosureService = new UiDisclosureService(this.vault);
    }

    public ToolCallback[] wrap(ToolCallback[] delegates) {
        ToolCallback[] wrapped = new ToolCallback[delegates.length];
        for (int i = 0; i < delegates.length; i++) {
            wrapped[i] = new SecureMcpToolCallback(delegates[i], this, this.objectMapper,
                    this.toolResultProcessor, this.traceLogger);
        }
        return wrapped;
    }

    public void bindToEgress() {
        SensitiveEgressFirewall.bind(this);
    }

    public void unbindFromEgress() {
        SensitiveEgressFirewall.unbind();
    }

    public String protectInput(String text) {
        return this.vault.protectText(text, this.detector.detect(text), RequestTokenVault.PiiOrigin.USER_INPUT);
    }

    public String protectOutput(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return this.vault.protectText(text, this.detector.detect(text), RequestTokenVault.PiiOrigin.MODEL_OUTPUT);
    }

    String resolveUserInputTokensForTool(String text) {
        return this.vault.resolveUserInputTokensForTool(text);
    }

    String restoreProtectedValues(String text) {
        return this.vault.resolveUserInputTokensForTool(text);
    }

    List<String> vaultedValuesPresentIn(String payload) {
        return this.vault.tokensLeakedIn(payload);
    }

    void validateToolCall(ToolCallIntent intent) {
        this.policy.validateToolCall(intent);
    }

    void recordToolInvocation() {
        this.toolInvocations.incrementAndGet();
    }

    void recordToolResult(ToolCallIntent intent, ToolResultProcessor.ProcessedToolResult result) {
        if ("read_records".equals(intent.toolName())) {
            recordRowsRead(intent.entity(), intent.filter(), result.rows());
        }
        if (intent.isTotalCountCall() && result.total() >= 0) {
            recordEntityTotal(intent.entity(), intent.filter(), result.total());
        }
    }

    public int toolInvocations() {
        return this.toolInvocations.get();
    }

    public long resolvedTotalCount() {
        if (this.totalsByScope.size() != 1) {
            return ChatResponse.UNKNOWN_TOTAL;
        }
        java.util.Map.Entry<String, Long> only = this.totalsByScope.entrySet().iterator().next();
        if (!this.rowsByScope.isEmpty() && !this.rowsByScope.containsKey(only.getKey())) {
            return ChatResponse.UNKNOWN_TOTAL;
        }
        return only.getValue();
    }

    public int protectedValueCount(String text) {
        return this.vault.protectedValueCount(text);
    }

    public int resolvedProtectedValueCount(String before, String after) {
        return this.vault.resolvedProtectedValueCount(before, after);
    }

    public String protectStructuredCell(String column, String value) {
        if (!StringUtils.hasText(value) || this.vault.protectedValueCount(value) > 0) {
            return value;
        }
        if (looksSensitiveColumn(column)) {
            return this.vault.protectKnown(value, typeFor(column), RequestTokenVault.PiiOrigin.MODEL_OUTPUT);
        }
        return protectOutput(value);
    }

    public ChatResponse toUiResponse(ChatResponse response) {
        return this.uiDisclosureService.toUiResponse(response);
    }

    void onStep(String step) {
        this.onStep.accept(step);
    }

    String requestId() {
        return this.requestId;
    }

    DataDisclosurePolicy policy() {
        return this.policy;
    }

    @Override
    public void close() {
        this.vault.close();
    }

    private void recordEntityTotal(String entity, String filter, long total) {
        String scope = scopeKey(entity, filter);
        if (scope != null && total >= 0) {
            this.totalsByScope.put(scope, total);
        }
    }

    private void recordRowsRead(String entity, String filter, int rows) {
        String scope = scopeKey(entity, filter);
        if (scope != null && rows >= 0) {
            this.rowsByScope.merge(scope, rows, Integer::sum);
        }
    }

    private static boolean looksSensitiveColumn(String column) {
        String normalized = column == null ? "" : column.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("email") || normalized.contains("phone")
                || normalized.contains("fullname") || normalized.contains("nameprotected")
                || normalized.endsWith("name");
    }

    private static PiiDetector.PiiType typeFor(String column) {
        String normalized = column == null ? "" : column.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("email")) {
            return PiiDetector.PiiType.EMAIL;
        }
        if (normalized.contains("phone")) {
            return PiiDetector.PiiType.PHONE;
        }
        if (normalized.contains("name")) {
            return PiiDetector.PiiType.NAME;
        }
        return PiiDetector.PiiType.VALUE;
    }

    private static String scopeKey(String entity, String filter) {
        if (!StringUtils.hasText(entity)) {
            return null;
        }
        String normalizedFilter = StringUtils.hasText(filter) ? filter.strip() : "";
        return entity.toLowerCase(java.util.Locale.ROOT) + '\0' + normalizedFilter;
    }
}
