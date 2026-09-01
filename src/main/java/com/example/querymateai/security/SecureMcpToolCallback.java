package com.example.querymateai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import com.example.querymateai.trace.LocalAiTraceLogger;

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
        return audited(toolInput, detokenizedToolInput -> this.delegate.call(detokenizedToolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return audited(toolInput, detokenizedToolInput -> this.delegate.call(detokenizedToolInput, toolContext));
    }

    private String audited(String toolInput, ToolInvocation invocation) {
        String name = getToolDefinition().name();
        this.session.recordToolInvocation();
        this.session.onStep(ToolCallIntent.describeStep(this.objectMapper, name, toolInput));
        String detokenizedToolInput = this.session.detokenize(toolInput);
        this.traceLogger.traceModelToolRequest(this.session.requestId(), name, toolInput);
        this.traceLogger.traceToolRequestAfterDetokenization(this.session.requestId(), toolInput,
                detokenizedToolInput, this.traceLogger.describeTokenResolution(toolInput, this.session.tokens()));
        long startedAt = System.nanoTime();
        String raw;
        try {
            raw = invocation.call(detokenizedToolInput);
        }
        catch (RuntimeException ex) {
            logger.error("MCP/DAB tool failed requestId={} tool={} errorType={}",
                    this.session.requestId(), name, ex.getClass().getSimpleName());
            throw ex;
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        int rows = resultRowCount(raw);
        this.traceLogger.traceRawToolResult(this.session.requestId(), name,
                ToolCallIntent.entityName(this.objectMapper, detokenizedToolInput), rows, latencyMs, raw);
        String protectedPayload = this.payloadProtector.protect(raw, this.session.tokens(), this.session.requestId());
        this.traceLogger.traceProtectedToolResult(this.session.requestId(), protectedPayload);
        return protectedPayload;
    }

    private int resultRowCount(String payload) {
        if (!StringUtils.hasText(payload)) {
            return -1;
        }
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            int direct = countValueArray(root);
            if (direct >= 0) {
                return direct;
            }
            JsonNode content = root.get("content");
            if (content instanceof ArrayNode array && !array.isEmpty()) {
                for (JsonNode item : array) {
                    JsonNode text = item.get("text");
                    if (text != null && text.isString()) {
                        JsonNode nested = this.objectMapper.readTree(text.stringValue());
                        int nestedCount = countValueArray(nested);
                        if (nestedCount >= 0) {
                            return nestedCount;
                        }
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            return -1;
        }
        return -1;
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

    private static String rowsDescription(int rows) {
        return rows >= 0 ? Integer.toString(rows) : "unknown";
    }

    @FunctionalInterface
    private interface ToolInvocation {

        String call(String detokenizedToolInput);
    }
}
