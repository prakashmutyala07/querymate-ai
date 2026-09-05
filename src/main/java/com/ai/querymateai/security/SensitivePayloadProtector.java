package com.ai.querymateai.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.ai.querymateai.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class SensitivePayloadProtector {

    private static final Logger logger = LoggerFactory.getLogger(SensitivePayloadProtector.class);

    private final ObjectMapper objectMapper;

    private final Map<String, String> prefixByField;

    private final AppProperties.DataPolicy policy;

    private final JavaPiiProtector piiProtector;

    SensitivePayloadProtector(ObjectMapper objectMapper, Map<String, String> prefixByField,
            AppProperties.DataPolicy policy, JavaPiiProtector piiProtector) {
        this.objectMapper = objectMapper;
        this.prefixByField = prefixByField;
        this.policy = policy;
        this.piiProtector = piiProtector;
    }

    /**
     * @param toolName decides the posture: results from a row-returning tool are protected
     * deny-by-default, while schema discovery is left alone so field names and descriptions
     * still reach the model intact.
     */
    String protect(String payload, String requestId, String toolName) {
        if (!StringUtils.hasText(payload)) {
            return payload;
        }
        boolean rowDataTool = this.policy.returnsRowData(toolName);
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            boolean protectedRows = walk(root, false, rowDataTool);
            if (rowDataTool && !protectedRows) {
                // The shape is not one the deny-by-default policy can prove safe. Withhold it:
                // named-field protection alone cannot cover a newly added confidential column.
                logger.warn("Row-data tool result had no recognised row array requestId={} tool={} "
                        + "rowArrayKeys={} action=withheld", requestId, toolName,
                        this.policy.rowArrayKeys());
                return "{\"error\":\"Tool result shape could not be verified and was withheld.\"}";
            }
            return this.objectMapper.writeValueAsString(root);
        }
        catch (RuntimeException ex) {
            logger.warn("Tool result protection failed requestId={} parseable=false action=withheld errorType={}",
                    requestId, ex.getClass().getSimpleName());
            return "{\"error\":\"Tool result could not be inspected for sensitive data and was withheld.\"}";
        }
    }

    /**
     * @param denyByDefault true once inside a row, where any column not declared safe is protected
     * @param promoteRowArrays whether a row-array key may switch {@code denyByDefault} on
     * @return whether any row array was actually reached
     */
    private boolean walk(JsonNode node, boolean denyByDefault, boolean promoteRowArrays) {
        boolean reachedRows = false;
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child == null) {
                    continue;
                }
                if (child.isString()) {
                    reachedRows |= protectString(object, name, child.stringValue(), denyByDefault,
                            promoteRowArrays);
                }
                else if (denyByDefault && child.isNumber()
                        && (!this.policy.isSafeColumn(name) || this.policy.isSensitiveNumericColumn(name))) {
                    object.put(name, this.piiProtector.protectKnownSensitiveValue(child.toString(), name));
                }
                else {
                    boolean rowArray = promoteRowArrays && !denyByDefault
                            && child instanceof ArrayNode && this.policy.isRowArrayKey(name);
                    reachedRows |= rowArray;
                    reachedRows |= walk(child, denyByDefault || rowArray, promoteRowArrays);
                }
            }
        }
        else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                reachedRows |= walk(child, denyByDefault, promoteRowArrays);
            }
        }
        return reachedRows;
    }

    private boolean protectString(ObjectNode object, String name, String value, boolean denyByDefault,
            boolean promoteRowArrays) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        // The MCP envelope carries its real payload as a JSON string: descend into it rather
        // than treating the whole blob as one opaque value.
        Embedded nested = protectEmbedded(value, denyByDefault, promoteRowArrays);
        if (nested != null) {
            object.put(name, nested.payload());
            return nested.reachedRows();
        }
        if (this.prefixByField.containsKey(name.toLowerCase())) {
            object.put(name, this.piiProtector.protectKnownSensitiveValue(value, name));
            return false;
        }
        if (denyByDefault && !this.policy.isSafeColumn(name)) {
            object.put(name, this.piiProtector.protectKnownSensitiveValue(value, name));
        }
        return false;
    }

    private Embedded protectEmbedded(String raw, boolean denyByDefault, boolean promoteRowArrays) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.length() < 2 || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            JsonNode nested = this.objectMapper.readTree(trimmed);
            boolean reachedRows = walk(nested, denyByDefault, promoteRowArrays);
            return new Embedded(this.objectMapper.writeValueAsString(nested), reachedRows);
        }
        catch (RuntimeException ex) {
            return new Embedded(
                    "{\"error\":\"Embedded tool result could not be inspected for sensitive data and was withheld.\"}",
                    false);
        }
    }

    private record Embedded(String payload, boolean reachedRows) {
    }

}
