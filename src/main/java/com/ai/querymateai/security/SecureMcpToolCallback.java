package com.ai.querymateai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Executes MCP tools through the local sensitive-data boundary.
 */
final class SecureMcpToolCallback implements ToolCallback {

    private static final Logger logger = LoggerFactory.getLogger(SecureMcpToolCallback.class);

    private final ToolCallback delegate;

    private final SensitiveRequestContext session;

    private final ObjectMapper objectMapper;

    private final SensitivePayloadProtector payloadProtector;

    private final LocalAiTraceLogger traceLogger;

    SecureMcpToolCallback(ToolCallback delegate, SensitiveRequestContext session, ObjectMapper objectMapper,
            SensitivePayloadProtector payloadProtector, LocalAiTraceLogger traceLogger) {
        this.delegate = delegate;
        this.session = session;
        this.objectMapper = objectMapper;
        this.payloadProtector = payloadProtector;
        this.traceLogger = traceLogger;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return this.delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return audited(toolInput, restoredToolInput -> this.delegate.call(restoredToolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return audited(toolInput, restoredToolInput -> this.delegate.call(restoredToolInput, toolContext));
    }

    private String audited(String toolInput, ToolInvocation invocation) {
        String name = getToolDefinition().name();
        this.session.recordToolInvocation();
        this.session.onStep(ToolCallIntent.describeStep(this.objectMapper, name, toolInput));
        this.traceLogger.traceModelToolRequest(this.session.requestId(), name, toolInput);
        String restoredToolInput;
        try {
            restoredToolInput = this.session.restoreProtectedValues(toolInput);
        }
        catch (RuntimeException ex) {
            logger.warn("MCP tool request protected-value restore failed requestId={} tool={} action=withheld errorType={}",
                    this.session.requestId(), name, ex.getClass().getSimpleName());
            return "{\"error\":\"Tool request contained an invalid protected value and was withheld.\"}";
        }
        this.traceLogger.traceToolRequestAfterDetokenization(this.session.requestId(), toolInput,
                restoredToolInput, this.session.resolvedProtectedValueCount(toolInput, restoredToolInput));
        long startedAt = System.nanoTime();
        String raw;
        try {
            raw = invocation.call(restoredToolInput);
        }
        catch (RuntimeException ex) {
            logger.error("MCP/DAB tool failed requestId={} tool={} errorType={}",
                    this.session.requestId(), name, ex.getClass().getSimpleName());
            throw ex;
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        int rows = resultRowCount(raw);
        recordResultTallies(name, restoredToolInput, raw, rows);
        this.traceLogger.traceRawToolResult(this.session.requestId(), name,
                ToolCallIntent.entityName(this.objectMapper, restoredToolInput), rows, latencyMs, raw);
        String protectedPayload = this.payloadProtector.protect(raw, this.session.requestId(), name);
        this.traceLogger.traceProtectedToolResult(this.session.requestId(), protectedPayload);
        return protectedPayload;
    }

    /**
     * Records what the tool actually returned, so the answer's row and total counts come from the
     * database rather than from the model's account of it.
     */
    private void recordResultTallies(String name, String toolInput, String raw, int rows) {
        String entity = ToolCallIntent.entityName(this.objectMapper, toolInput);
        String filter = filterValue(toolInput);
        if ("read_records".equals(name)) {
            this.session.recordRowsRead(entity, filter, rows);
            return;
        }
        if ("aggregate_records".equals(name) && isTotalCountCall(toolInput)) {
            long total = aggregateCount(raw);
            if (total >= 0) {
                this.session.recordEntityTotal(entity, filter, total);
            }
        }
    }

    private String filterValue(String toolInput) {
        try {
            JsonNode args = this.objectMapper.readTree(toolInput);
            JsonNode filter = args.get("filter") != null ? args.get("filter") : args.get("$filter");
            return stringValue(filter);
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Whether the call counts every matching record. A grouped count produces one number per
     * group, and a count over a named field can skip nulls, so neither is the answer's total.
     */
    private boolean isTotalCountCall(String toolInput) {
        try {
            JsonNode args = this.objectMapper.readTree(toolInput);
            JsonNode groupby = args.get("groupby");
            if (groupby instanceof ArrayNode grouped && !grouped.isEmpty()) {
                return false;
            }
            return "count".equalsIgnoreCase(stringValue(args.get("function")))
                    && "*".equals(stringValue(args.get("field")));
        }
        catch (RuntimeException ex) {
            return false;
        }
    }

    private long aggregateCount(String payload) {
        if (!StringUtils.hasText(payload)) {
            return -1L;
        }
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            long direct = countValue(root);
            if (direct >= 0) {
                return direct;
            }
            if (root instanceof ArrayNode array) {
                for (JsonNode item : array) {
                    long nested = firstNonNegative(countValue(item), countFromText(item));
                    if (nested >= 0) {
                        return nested;
                    }
                }
            }
            if (root.get("content") instanceof ArrayNode content) {
                for (JsonNode item : content) {
                    long nested = countFromText(item);
                    if (nested >= 0) {
                        return nested;
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            return -1L;
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
            return -1L;
        }
        JsonNode count = array.get(0) == null ? null : array.get(0).get("count");
        return count != null && count.isNumber() ? count.asLong() : -1L;
    }

    private static long firstNonNegative(long first, long second) {
        return first >= 0 ? first : second;
    }

    private static String stringValue(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private int resultRowCount(String payload) {
        if (!StringUtils.hasText(payload)) {
            return -1;
        }
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            if (root instanceof ArrayNode array) {
                for (JsonNode item : array) {
                    int count = countValueArray(item);
                    if (count >= 0) {
                        return count;
                    }
                    count = countTextPayload(item);
                    if (count >= 0) {
                        return count;
                    }
                }
            }
            int direct = countValueArray(root);
            if (direct >= 0) {
                return direct;
            }
            JsonNode content = root.get("content");
            if (content instanceof ArrayNode array && !array.isEmpty()) {
                for (JsonNode item : array) {
                    int nestedCount = countTextPayload(item);
                    if (nestedCount >= 0) {
                        return nestedCount;
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            return -1;
        }
        return -1;
    }

    private int countTextPayload(JsonNode item) {
        JsonNode text = item == null ? null : item.get("text");
        if (text == null || !text.isString()) {
            return -1;
        }
        try {
            return countValueArray(this.objectMapper.readTree(text.stringValue()));
        }
        catch (RuntimeException ex) {
            return -1;
        }
    }

    private static int countValueArray(JsonNode node) {
        if (node == null) {
            return -1;
        }
        JsonNode value = node.get("value");
        if (value instanceof ArrayNode array) {
            return array.size();
        }
        JsonNode result = node.get("result");
        if (result != null) {
            JsonNode resultValue = result.get("value");
            if (resultValue instanceof ArrayNode array) {
                return array.size();
            }
        }
        return -1;
    }

    @FunctionalInterface
    private interface ToolInvocation {

        String call(String restoredToolInput);
    }
}
