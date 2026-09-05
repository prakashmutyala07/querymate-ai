package com.ai.querymateai.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

public record ToolCallIntent(String toolName, JsonNode arguments) {

    public static ToolCallIntent parse(ObjectMapper objectMapper, String toolName, String toolInput) {
        JsonNode args = StringUtils.hasText(toolInput) ? objectMapper.readTree(toolInput) : objectMapper.createObjectNode();
        return new ToolCallIntent(toolName, args);
    }

    public String entity() {
        JsonNode node = this.arguments.get("entity") != null ? this.arguments.get("entity")
                : this.arguments.get("entityName");
        return node != null && node.isString() ? node.stringValue() : null;
    }

    public String filter() {
        JsonNode node = this.arguments.get("filter") != null ? this.arguments.get("filter")
                : this.arguments.get("$filter");
        return node != null && node.isString() ? node.stringValue() : null;
    }

    public int requestedRows() {
        int top = intValue(this.arguments.get("top"));
        if (top < 0) {
            top = intValue(this.arguments.get("$top"));
        }
        if (top < 0) {
            top = intValue(this.arguments.get("limit"));
        }
        if (top < 0) {
            top = intValue(this.arguments.get("first"));
        }
        return Math.max(top, 0);
    }

    public boolean isTotalCountCall() {
        JsonNode groupby = this.arguments.get("groupby");
        if (groupby instanceof ArrayNode grouped && !grouped.isEmpty()) {
            return false;
        }
        return "aggregate_records".equals(this.toolName)
                && "count".equalsIgnoreCase(stringValue(this.arguments.get("function")))
                && "*".equals(stringValue(this.arguments.get("field")));
    }

    public String describeStep() {
        String entity = entity();
        return switch (this.toolName) {
            case "describe_entities" -> "Reading entity metadata...";
            case "aggregate_records" -> entity == null ? "Aggregating records..."
                    : "Aggregating " + entity + " records...";
            case "read_records" -> entity == null ? "Reading records..." : "Reading " + entity + " records...";
            default -> "Calling " + this.toolName + "...";
        };
    }

    public String renderSafe() {
        if (this.arguments == null || !this.arguments.isObject()) {
            return "{}";
        }
        Map<String, String> intent = new HashMap<>();
        for (String key : List.of("entity", "entityName", "filter", "$filter", "orderby", "select", "top", "$top")) {
            JsonNode value = this.arguments.get(key);
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
        return intent.isEmpty() ? "keys=" + new TreeSet<>(this.arguments.propertyNames()) : intent.toString();
    }

    private static int intValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return -1;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isString()) {
            try {
                return Integer.parseInt(node.stringValue());
            }
            catch (NumberFormatException ex) {
                return -1;
            }
        }
        return -1;
    }

    private static String stringValue(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
