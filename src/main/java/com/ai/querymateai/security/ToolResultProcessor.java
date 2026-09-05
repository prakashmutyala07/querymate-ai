package com.ai.querymateai.security;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.ai.querymateai.mcp.ToolCallIntent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class ToolResultProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ToolResultProcessor.class);

    private final ObjectMapper objectMapper;

    private final DataDisclosurePolicy policy;

    private final RequestTokenVault vault;

    ToolResultProcessor(ObjectMapper objectMapper, DataDisclosurePolicy policy, RequestTokenVault vault) {
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.vault = vault;
    }

    ProcessedToolResult process(String payload, String requestId, ToolCallIntent intent, long latencyMs) {
        if (!StringUtils.hasText(payload)) {
            return new ProcessedToolResult(payload, -1, -1L);
        }
        try {
            DataDisclosurePolicy.ensurePayloadWithinLimit(payload, this.policy.limits().maxToolResultBytes(),
                    "Tool result");
            JsonNode root = this.objectMapper.readTree(payload);
            State state = new State(intent.entity());
            boolean reachedRows = walk(root, false, this.policy.returnsRowData(intent.toolName()), state);
            if (this.policy.returnsRowData(intent.toolName()) && !reachedRows) {
                logger.warn("Row-data tool result had no recognised row array requestId={} tool={} action=withheld",
                        requestId, intent.toolName());
                return ProcessedToolResult.withheld("Tool result shape could not be verified and was withheld.");
            }
            long total = intent.isTotalCountCall() ? aggregateCount(root) : -1L;
            logger.info("Tool result protected requestId={} tool={} rows={} total={} durationMs={}",
                    requestId, intent.toolName(), state.rows, total, latencyMs);
            return new ProcessedToolResult(this.objectMapper.writeValueAsString(root), state.rows, total);
        }
        catch (DataDisclosurePolicy.PolicyViolationException ex) {
            logger.warn("Tool result withheld requestId={} tool={} reason={}", requestId, intent.toolName(),
                    ex.getMessage());
            return ProcessedToolResult.withheld(ex.getMessage());
        }
        catch (RuntimeException ex) {
            logger.warn("Tool result protection failed requestId={} tool={} action=withheld errorType={}",
                    requestId, intent.toolName(), ex.getClass().getSimpleName());
            return ProcessedToolResult.withheld("Tool result could not be inspected for sensitive data and was withheld.");
        }
    }

    private boolean walk(JsonNode node, boolean insideRow, boolean promoteRowArrays, State state) {
        boolean reachedRows = false;
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child == null) {
                    continue;
                }
                if (child.isString()) {
                    reachedRows |= protectString(object, name, child.stringValue(), insideRow, promoteRowArrays, state);
                    continue;
                }
                if (insideRow && !child.isNull()) {
                    protectNonString(object, name, child, state.entity);
                    continue;
                }
                boolean rowArray = promoteRowArrays && child instanceof ArrayNode && this.policy.isRowArrayKey(name);
                if (rowArray) {
                    reachedRows = true;
                    state.rows += ((ArrayNode) child).size();
                }
                reachedRows |= walk(child, insideRow || rowArray, promoteRowArrays, state);
            }
            return reachedRows;
        }
        if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                reachedRows |= walk(child, insideRow, promoteRowArrays, state);
            }
        }
        return reachedRows;
    }

    private boolean protectString(ObjectNode object, String name, String value, boolean insideRow,
            boolean promoteRowArrays, State state) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        Embedded embedded = protectEmbedded(value, insideRow, promoteRowArrays, state);
        if (embedded != null) {
            object.put(name, embedded.payload());
            return embedded.reachedRows();
        }
        if (!insideRow) {
            return false;
        }
        DataDisclosurePolicy.Decision decision = this.policy.decide(state.entity, name, object.get(name));
        if (decision.action() == DataDisclosurePolicy.Action.TOKENIZE) {
            object.put(name, this.vault.protectKnown(value, decision.type(),
                    RequestTokenVault.PiiOrigin.AUTHORIZED_TOOL_RESULT));
        }
        return false;
    }

    private void protectNonString(ObjectNode object, String name, JsonNode value, String entity) {
        DataDisclosurePolicy.Decision decision = this.policy.decide(entity, name, value);
        if (decision.action() == DataDisclosurePolicy.Action.TOKENIZE) {
            object.put(name, this.vault.protectKnown(value.toString(), decision.type(),
                    RequestTokenVault.PiiOrigin.AUTHORIZED_TOOL_RESULT));
        }
    }

    private Embedded protectEmbedded(String raw, boolean insideRow, boolean promoteRowArrays, State state) {
        String trimmed = raw.strip();
        if (trimmed.length() < 2 || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            JsonNode nested = this.objectMapper.readTree(trimmed);
            boolean reachedRows = walk(nested, insideRow, promoteRowArrays, state);
            return new Embedded(this.objectMapper.writeValueAsString(nested), reachedRows);
        }
        catch (RuntimeException ex) {
            return new Embedded(
                    "{\"error\":\"Embedded tool result could not be inspected for sensitive data and was withheld.\"}",
                    false);
        }
    }

    private long aggregateCount(JsonNode root) {
        long direct = countValue(root);
        if (direct >= 0) {
            return direct;
        }
        if (root instanceof ArrayNode array) {
            for (JsonNode item : array) {
                long nested = Math.max(countValue(item), countFromText(item));
                if (nested >= 0) {
                    return nested;
                }
            }
        }
        JsonNode content = root.get("content");
        if (content instanceof ArrayNode array) {
            for (JsonNode item : array) {
                long nested = countFromText(item);
                if (nested >= 0) {
                    return nested;
                }
            }
        }
        return -1L;
    }

    private long countFromText(JsonNode item) {
        JsonNode text = item == null ? null : item.get("text");
        if (text == null || !text.isString()) {
            return -1L;
        }
        try {
            return countValue(this.objectMapper.readTree(text.stringValue()));
        }
        catch (RuntimeException ex) {
            return -1L;
        }
    }

    private static long countValue(JsonNode node) {
        if (node == null) {
            return -1L;
        }
        JsonNode result = node.get("result");
        JsonNode rows = result instanceof ArrayNode ? result : node.get("value");
        if (!(rows instanceof ArrayNode array) || array.isEmpty()) {
            JsonNode nested = result == null ? null : result.get("value");
            rows = nested instanceof ArrayNode ? nested : null;
        }
        if (!(rows instanceof ArrayNode array) || array.isEmpty()) {
            return -1L;
        }
        JsonNode count = array.get(0) == null ? null : array.get(0).get("count");
        if (count == null) {
            return -1L;
        }
        if (count.isNumber()) {
            return count.asLong();
        }
        if (count.isString()) {
            try {
                return Long.parseLong(count.stringValue());
            }
            catch (NumberFormatException ex) {
                return -1L;
            }
        }
        return -1L;
    }

    private static final class State {

        private final String entity;

        private int rows = 0;

        private State(String entity) {
            this.entity = entity;
        }
    }

    record ProcessedToolResult(String payload, int rows, long total) {

        static ProcessedToolResult withheld(String message) {
            return new ProcessedToolResult("{\"error\":\"" + message + "\"}", -1, -1L);
        }
    }

    private record Embedded(String payload, boolean reachedRows) {
    }
}
