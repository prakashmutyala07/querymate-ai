package com.ai.querymateai.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.mcp.ToolCallIntent;

import tools.jackson.databind.JsonNode;

final class DataDisclosurePolicy {

    private static final Set<String> ALLOWED_TOOLS =
            Set.of("describe_entities", "read_records", "aggregate_records");

    private final AppProperties.DataPolicy dataPolicy;

    private final AppProperties.Limits limits;

    private final Map<String, AppProperties.SensitiveField> sensitiveFields;

    DataDisclosurePolicy(AppProperties properties) {
        this.dataPolicy = properties.security().dataPolicy();
        this.limits = properties.security().limits();
        this.sensitiveFields = properties.sensitiveFields().stream()
                .collect(Collectors.toMap(field -> key(field.entity(), field.field()), field -> field,
                        (first, second) -> first));
    }

    AppProperties.Limits limits() {
        return this.limits;
    }

    boolean returnsRowData(String toolName) {
        return this.dataPolicy.returnsRowData(toolName);
    }

    boolean isRowArrayKey(String key) {
        return this.dataPolicy.isRowArrayKey(key);
    }

    void validateToolCall(ToolCallIntent intent) {
        if (!ALLOWED_TOOLS.contains(intent.toolName())) {
            throw new PolicyViolationException("Tool is not allowlisted.");
        }
        if (containsAnyKey(intent.arguments(), Set.of("user", "username", "userId", "roles", "tenantId"))) {
            throw new PolicyViolationException("Tool request attempted to provide identity/authorization fields.");
        }
        int requestedRows = intent.requestedRows();
        if (requestedRows > this.limits.maxRows()) {
            throw new PolicyViolationException("Tool request row limit exceeds configured maximum.");
        }
    }

    Decision decide(String entity, String field, JsonNode value) {
        if (value == null || value.isNull()) {
            return Decision.pass();
        }
        AppProperties.SensitiveField configured = this.sensitiveFields.get(key(entity, field));
        if (configured != null) {
            return Decision.tokenize(typeFor(field));
        }
        if (value.isNumber() && this.dataPolicy.isSensitiveNumericColumn(field)) {
            return Decision.tokenize(PiiDetector.PiiType.VALUE);
        }
        if (this.dataPolicy.isSafeField(entity, field)) {
            return Decision.pass();
        }
        return Decision.tokenize(typeFor(field));
    }

    private static boolean containsAnyKey(JsonNode node, Set<String> blockedKeys) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (String key : node.propertyNames()) {
                if (blockedKeys.contains(key)) {
                    return true;
                }
                if (containsAnyKey(node.get(key), blockedKeys)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsAnyKey(child, blockedKeys)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PiiDetector.PiiType typeFor(String field) {
        String normalized = field == null ? "" : field.toLowerCase(Locale.ROOT);
        if (normalized.contains("email")) {
            return PiiDetector.PiiType.EMAIL;
        }
        if (normalized.contains("phone") || normalized.contains("mobile")) {
            return PiiDetector.PiiType.PHONE;
        }
        if (normalized.contains("name")) {
            return PiiDetector.PiiType.NAME;
        }
        return PiiDetector.PiiType.VALUE;
    }

    private static String key(String entity, String field) {
        return normalized(entity) + "." + normalized(field);
    }

    private static String normalized(String value) {
        return StringUtils.hasText(value) ? value.toLowerCase(Locale.ROOT) : "";
    }

    static void ensurePayloadWithinLimit(String payload, int maxBytes, String label) {
        int bytes = payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new PolicyViolationException(label + " exceeded configured byte limit.");
        }
    }

    record Decision(Action action, PiiDetector.PiiType type) {

        static Decision pass() {
            return new Decision(Action.PASS, null);
        }

        static Decision tokenize(PiiDetector.PiiType type) {
            return new Decision(Action.TOKENIZE, type == null ? PiiDetector.PiiType.VALUE : type);
        }
    }

    enum Action {
        PASS,
        TOKENIZE
    }

    static final class PolicyViolationException extends RuntimeException {

        PolicyViolationException(String message) {
            super(message);
        }
    }
}
