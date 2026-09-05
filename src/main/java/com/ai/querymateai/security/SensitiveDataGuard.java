package com.ai.querymateai.security;

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

    private final PiiDetector detector;

    private final DataDisclosurePolicy policy;

    private final Set<String> protectedFields;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper,
            LocalAiTraceLogger traceLogger) {
        this.objectMapper = objectMapper;
        this.traceLogger = traceLogger;
        this.detector = new PiiDetector(properties.security().detection().phoneRegion(),
                properties.security().detection().requirePersonNameModel());
        this.policy = new DataDisclosurePolicy(properties);
        this.protectedFields = properties.sensitiveFields().stream()
                .map(field -> field.field().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        AppProperties.DataPolicy dataPolicy = properties.security().dataPolicy();
        logger.info("Request-local PII protection active: {} named sensitive field(s) {}, "
                        + "row tools={} globalSafeColumns={} entityPolicies={} maxRows={} maxToolResultBytes={}",
                this.protectedFields.size(), this.protectedFields, dataPolicy.rowDataTools(),
                dataPolicy.safeColumns().size(), dataPolicy.safeFieldsByEntity().keySet(),
                properties.security().limits().maxRows(), properties.security().limits().maxToolResultBytes());
        if (!PiiDetector.personNameModelAvailable()) {
            // Silence here would be worse than the gap: the rest of the startup log reads as if
            // every detector were running.
            logger.warn("Apache OpenNLP person-name model 'en-ner-person.bin' is NOT on the classpath. "
                    + "Person-name detection in free text falls back to the cue-word pattern alone; "
                    + "names phrased outside that pattern will not be detected in user input.");
        }
    }

    public PrivacySession newSession() {
        return newSession("none", step -> {
        });
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public PrivacySession newSession(java.util.function.Consumer<String> onStep) {
        return newSession("none", onStep);
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public PrivacySession newSession(String requestId, java.util.function.Consumer<String> onStep) {
        return new PrivacySession(StringUtils.hasText(requestId) ? requestId : "none", onStep,
                this.objectMapper, this.traceLogger, this.detector, this.policy);
    }

    public Set<String> protectedFields() {
        return this.protectedFields;
    }
}
