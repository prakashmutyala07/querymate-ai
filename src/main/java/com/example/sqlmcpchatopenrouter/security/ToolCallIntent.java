package com.example.sqlmcpchatopenrouter.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ToolCallIntent {

    private ToolCallIntent() {
    }

    static String describeStep(ObjectMapper objectMapper, String name, String toolInput) {
        String entity = entityName(objectMapper, toolInput);
        return switch (name) {
            case "describe_entities" -> "Reading entity metadata...";
            case "aggregate_records" -> entity == null ? "Aggregating records..."
                    : "Aggregating " + entity + " records...";
            case "read_records" -> entity == null ? "Reading records..." : "Reading " + entity + " records...";
            default -> "Calling " + name + "...";
        };
    }

    static String render(ObjectMapper objectMapper, String toolInput) {
        if (!StringUtils.hasText(toolInput)) {
            return "{}";
        }
        try {
            JsonNode args = objectMapper.readTree(toolInput);
            Map<String, String> intent = new HashMap<>();
            for (String key : List.of("entity", "entityName", "filter", "$filter", "orderby", "select")) {
                JsonNode value = args.get(key);
                if (value == null || value.isNull()) {
                    continue;
                }
                if (key.equals("filter") || key.equals("$filter")) {
                    intent.put(key, "<redacted>");
                }
                else {
                    intent.put(key, value.isString() ? value.stringValue() : value.toString());
                }
            }
            return intent.isEmpty() ? "keys=" + new TreeSet<>(args.propertyNames()) : intent.toString();
        }
        catch (RuntimeException ex) {
            return "<unparseable>";
        }
    }

    static int resolvedTokenCount(String protectedInput, String detokenizedInput) {
        return SensitiveTokenStore.resolvedTokenCount(protectedInput, detokenizedInput);
    }

    static List<String> keys(ObjectMapper objectMapper, String toolInput) {
        if (!StringUtils.hasText(toolInput)) {
            return List.of();
        }
        try {
            JsonNode args = objectMapper.readTree(toolInput);
            return new TreeSet<>(args.propertyNames()).stream().toList();
        }
        catch (RuntimeException ex) {
            return List.of("<unparseable>");
        }
    }

    static String entityName(ObjectMapper objectMapper, String toolInput) {
        try {
            JsonNode args = objectMapper.readTree(toolInput);
            JsonNode node = args.get("entity") != null ? args.get("entity") : args.get("entityName");
            return node != null && node.isString() ? node.stringValue() : null;
        }
        catch (RuntimeException ex) {
            return null;
        }
    }
}
