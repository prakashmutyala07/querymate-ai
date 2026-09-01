package com.example.sqlmcpchatopenrouter.security;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.trace.LocalAiTraceLogger;

import tools.jackson.databind.ObjectMapper;

/**
 * Public facade for request-local sensitive-data protection.
 *
 * <p>Interception happens at the {@link ToolCallback} boundary rather than in a
 * {@code CallAdvisor}, because tool execution runs inside the model's tool-calling loop:
 * by the time an advisor sees a response the raw rows would already have been sent
 * upstream. Wrapping the callback is the last point where that can still be prevented.
 */
@Component
public class SensitiveDataGuard {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveDataGuard.class);

    private final ObjectMapper objectMapper;

    private final LocalAiTraceLogger traceLogger;

    /** Lower-cased field name -> token prefix. Matching is by field name across all entities. */
    private final Map<String, String> prefixByField;

    private final byte[] secretKey;

    private final PiiDetector piiDetector = new PiiDetector();

    private final SensitivePayloadProtector payloadProtector;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper,
            LocalAiTraceLogger traceLogger) {
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.prefixByField = properties.sensitiveFields().stream()
                .collect(Collectors.toMap(field -> field.field().toLowerCase(),
                        AppProperties.SensitiveField::prefixOrDefault, (first, second) -> first));
        String configured = properties.security().tokenSecretKey();
        if (!StringUtils.hasText(configured) && !this.prefixByField.isEmpty()) {
            throw new IllegalStateException(
                    "TOKEN_SECRET_KEY must be set when app.sensitive-fields is non-empty.");
        }
        this.secretKey = StringUtils.hasText(configured)
                ? configured.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.payloadProtector = new SensitivePayloadProtector(objectMapper, this.prefixByField);
        logger.info("Sensitive-field redaction active for {} field(s): {}", this.prefixByField.size(),
                this.prefixByField.keySet());
    }

    public SensitiveRequestContext newSession() {
        return newSession("none", step -> {
        });
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public SensitiveRequestContext newSession(java.util.function.Consumer<String> onStep) {
        return newSession("none", onStep);
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public SensitiveRequestContext newSession(String requestId, java.util.function.Consumer<String> onStep) {
        return new SensitiveRequestContext(StringUtils.hasText(requestId) ? requestId : "none", onStep,
                this.secretKey, this.objectMapper, this.traceLogger, this.prefixByField,
                this.piiDetector, this.payloadProtector);
    }

    public Set<String> protectedFields() {
        return this.prefixByField.keySet();
    }
}
