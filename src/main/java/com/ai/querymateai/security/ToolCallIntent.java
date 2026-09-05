package com.ai.querymateai.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ToolCallIntent {

    private static final Pattern PROTECTED_VALUE =
            Pattern.compile("\\b(?:CustomerName|Email|Phone|SensitiveValue)Protected#\\d+\\b");

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

    static int resolvedTokenCount(String protectedInput, String decryptedInput) {
        if (!StringUtils.hasText(protectedInput) || !StringUtils.hasText(decryptedInput)
                || protectedInput.equals(decryptedInput)) {
            return 0;
        }
        Matcher matcher = PROTECTED_VALUE.matcher(protectedInput);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
