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

    private final AppProperties.DataPolicy dataPolicy;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper,
            LocalAiTraceLogger traceLogger) {
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.prefixByField = properties.sensitiveFields().stream()
                .collect(Collectors.toMap(field -> field.field().toLowerCase(),
                        AppProperties.SensitiveField::prefixOrDefault, (first, second) -> first));
        this.dataPolicy = properties.security().dataPolicy();
        logger.info("Java request-local PII protection active: {} named sensitive field(s) {}, "
                        + "deny-by-default row protection for tool(s) {} with {} safe column(s) {}",
                this.prefixByField.size(), this.prefixByField.keySet(), this.dataPolicy.rowDataTools(),
                this.dataPolicy.safeColumns().size(), this.dataPolicy.safeColumns());
        if (!JavaPiiProtector.personNameModelAvailable()) {
            // Silence here would be worse than the gap: the rest of the startup log reads as if
            // every detector were running.
            logger.warn("Apache OpenNLP person-name model 'en-ner-person.bin' is NOT on the classpath. "
                    + "Person-name detection in free text falls back to the cue-word pattern alone; "
                    + "names phrased outside that pattern will not be detected in user input.");
        }
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
        // A protector per turn: the token vault holds raw PII, so it must not outlive the
        // request or be visible to another one.
        JavaPiiProtector piiProtector = new JavaPiiProtector();
        SensitivePayloadProtector payloadProtector = new SensitivePayloadProtector(this.objectMapper,
                this.prefixByField, this.dataPolicy, piiProtector);
        return new SensitiveRequestContext(StringUtils.hasText(requestId) ? requestId : "none", onStep,
                this.objectMapper, this.traceLogger, piiProtector, payloadProtector,
                this.prefixByField.keySet());
    }

    public Set<String> protectedFields() {
        return this.prefixByField.keySet();
    }
}
