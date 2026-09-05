package com.ai.querymateai.security;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.trace.LocalAiTraceLogger;

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

    private final JavaPiiProtector piiProtector;

    private final SensitivePayloadProtector payloadProtector;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper,
            LocalAiTraceLogger traceLogger) {
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.prefixByField = properties.sensitiveFields().stream()
                .collect(Collectors.toMap(field -> field.field().toLowerCase(),
                        AppProperties.SensitiveField::prefixOrDefault, (first, second) -> first));
        this.piiProtector = new JavaPiiProtector();
        this.payloadProtector = new SensitivePayloadProtector(objectMapper, this.prefixByField,
                this.piiProtector);
        logger.info("Java request-local PII protection active for {} configured sensitive field(s): {}",
                this.prefixByField.size(), this.prefixByField.keySet());
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
                this.objectMapper, this.traceLogger, this.piiProtector, this.payloadProtector,
                this.prefixByField.keySet());
    }

    public Set<String> protectedFields() {
        return this.prefixByField.keySet();
    }
}
