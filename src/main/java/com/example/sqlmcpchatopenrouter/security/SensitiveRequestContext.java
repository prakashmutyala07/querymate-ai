package com.example.sqlmcpchatopenrouter.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.trace.LocalAiTraceLogger;

import tools.jackson.databind.ObjectMapper;

/**
 * Per-request sensitive-data state. One instance per chat turn; discarded when the turn ends.
 */
public final class SensitiveRequestContext {

    private final SensitiveTokenStore tokens;

    private final java.util.concurrent.atomic.AtomicInteger toolInvocations =
            new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.function.Consumer<String> onStep;

    private final String requestId;

    private final ObjectMapper objectMapper;

    private final LocalAiTraceLogger traceLogger;

    private final Map<String, String> prefixByField;

    private final PiiDetector piiDetector;

    private final SensitivePayloadProtector payloadProtector;

    SensitiveRequestContext(String requestId, java.util.function.Consumer<String> onStep, byte[] secretKey,
            ObjectMapper objectMapper, LocalAiTraceLogger traceLogger, Map<String, String> prefixByField,
            PiiDetector piiDetector, SensitivePayloadProtector payloadProtector) {
        this.requestId = requestId;
        this.onStep = onStep;
        this.tokens = new SensitiveTokenStore(secretKey);
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.prefixByField = prefixByField;
        this.piiDetector = piiDetector;
        this.payloadProtector = payloadProtector;
    }

    /** Decorates each MCP tool so its result is tokenized and audited before the model sees it. */
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
        Map<String, String> existingTokens = this.tokens.snapshot();
        String protectedText = this.piiDetector.protect(text, this.tokens, this.prefixByField);
        this.traceLogger.tracePiiProtection(this.requestId, text,
                this.traceLogger.describeNewTokens(this.tokens, existingTokens),
                inputTokenMap(this.tokens, existingTokens), protectedText);
        return protectedText;
    }

    /** Final defense for provider-generated email/phone text. Database tokens stay pseudonymized. */
    public String protectOutput(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String protectedText = this.tokens.protectKnownValues(text);
        return this.piiDetector.redactProviderContactDetails(protectedText);
    }

    /** Restores request-local tokens only after model calls and sanitized memory writes are complete. */
    public String revealForTrustedLocalDisplay(String text) {
        return this.tokens.detokenize(text);
    }

    public int tokenCount() {
        return this.tokens.size();
    }

    /** How many tool calls actually executed this turn. Ground truth for usedDatabaseTools. */
    public int toolInvocations() {
        return this.toolInvocations.get();
    }

    SensitiveTokenStore tokens() {
        return this.tokens;
    }

    Map<String, String> tokenSnapshot() {
        return this.tokens.snapshot();
    }

    String detokenize(String text) {
        return this.tokens.detokenize(text);
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

    static String detectedEntityValues(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        Map<String, String> values = newTokenSnapshot(tokens, existingTokens);
        return values.isEmpty() ? "none" : values.entrySet().stream()
                .map(entry -> entry.getKey().substring(0, entry.getKey().indexOf('_'))
                        + " = " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    static String inputTokenMap(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        Map<String, String> values = newTokenSnapshot(tokens, existingTokens);
        return values.isEmpty() ? "none" : values.entrySet().stream()
                .map(entry -> entry.getValue() + " -> " + entry.getKey())
                .collect(Collectors.joining("\n"));
    }

    private static List<String> newlyCreatedTokens(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        return new ArrayList<>(newTokenSnapshot(tokens, existingTokens).keySet());
    }

    private static Map<String, String> newTokenSnapshot(SensitiveTokenStore tokens,
            Map<String, String> existingTokens) {
        return tokens.snapshot().entrySet().stream()
                .filter(entry -> !existingTokens.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, LinkedHashMap::new));
    }

}
