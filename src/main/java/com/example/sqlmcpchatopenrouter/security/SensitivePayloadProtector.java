package com.example.sqlmcpchatopenrouter.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class SensitivePayloadProtector {

    private static final Logger logger = LoggerFactory.getLogger(SensitivePayloadProtector.class);

    private final ObjectMapper objectMapper;

    private final Map<String, String> prefixByField;

    SensitivePayloadProtector(ObjectMapper objectMapper, Map<String, String> prefixByField) {
        this.objectMapper = objectMapper;
        this.prefixByField = prefixByField;
    }

    String protect(String payload, SensitiveTokenStore tokens, String requestId) {
        if (!StringUtils.hasText(payload)) {
            return payload;
        }
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            walk(root, tokens);
            return this.objectMapper.writeValueAsString(root);
        }
        catch (RuntimeException ex) {
            logger.warn("Tool result protection failed requestId={} parseable=false action=withheld errorType={}",
                    requestId, ex.getClass().getSimpleName());
            return "{\"error\":\"Tool result could not be inspected for sensitive data and was withheld.\"}";
        }
    }

    private void walk(JsonNode node, SensitiveTokenStore tokens) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child == null) {
                    continue;
                }
                String prefix = this.prefixByField.get(name.toLowerCase());
                if (prefix != null && child.isString() && StringUtils.hasText(child.stringValue())) {
                    object.put(name, tokens.tokenFor(name, prefix, child.stringValue()));
                }
                else if (child.isString()) {
                    String nested = protectEmbedded(child.stringValue(), tokens);
                    if (nested != null) {
                        object.put(name, nested);
                    }
                }
                else {
                    walk(child, tokens);
                }
            }
        }
        else if (node instanceof ArrayNode array) {
            array.forEach(child -> walk(child, tokens));
        }
    }

    private String protectEmbedded(String raw, SensitiveTokenStore tokens) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.length() < 2 || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            JsonNode nested = this.objectMapper.readTree(trimmed);
            walk(nested, tokens);
            return this.objectMapper.writeValueAsString(nested);
        }
        catch (RuntimeException ex) {
            return "{\"error\":\"Embedded tool result could not be inspected for sensitive data and was withheld.\"}";
        }
    }

}
