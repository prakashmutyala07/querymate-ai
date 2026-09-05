package com.ai.querymateai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import com.ai.querymateai.mcp.ToolCallIntent;

import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.ObjectMapper;

/**
 * Executes MCP tools through the local sensitive-data boundary.
 */
final class SecureMcpToolCallback implements ToolCallback {

    private static final Logger logger = LoggerFactory.getLogger(SecureMcpToolCallback.class);

    private final ToolCallback delegate;

    private final PrivacySession session;

    private final ObjectMapper objectMapper;

    private final ToolResultProcessor toolResultProcessor;

    private final LocalAiTraceLogger traceLogger;

    SecureMcpToolCallback(ToolCallback delegate, PrivacySession session, ObjectMapper objectMapper,
            ToolResultProcessor toolResultProcessor, LocalAiTraceLogger traceLogger) {
        this.delegate = delegate;
        this.session = session;
        this.objectMapper = objectMapper;
        this.toolResultProcessor = toolResultProcessor;
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
        ToolCallIntent intent;
        try {
            intent = ToolCallIntent.parse(this.objectMapper, name, toolInput);
            this.session.validateToolCall(intent);
        }
        catch (RuntimeException ex) {
            logger.warn("MCP tool request failed policy validation requestId={} tool={} action=withheld errorType={}",
                    this.session.requestId(), name, ex.getClass().getSimpleName());
            return "{\"error\":\"Tool request failed privacy policy validation and was withheld.\"}";
        }
        this.session.recordToolInvocation();
        this.session.onStep(intent.describeStep());
        this.traceLogger.traceModelToolRequest(this.session.requestId(), name, toolInput);
        String restoredToolInput;
        try {
            restoredToolInput = this.session.resolveUserInputTokensForTool(toolInput);
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
        ToolCallIntent restoredIntent = ToolCallIntent.parse(this.objectMapper, name, restoredToolInput);
        ToolResultProcessor.ProcessedToolResult processed =
                this.toolResultProcessor.process(raw, this.session.requestId(), restoredIntent, latencyMs);
        this.session.recordToolResult(restoredIntent, processed);
        this.traceLogger.traceRawToolResult(this.session.requestId(), name,
                restoredIntent.entity(), processed.rows(), latencyMs, raw);
        this.traceLogger.traceProtectedToolResult(this.session.requestId(), processed.payload());
        return processed.payload();
    }

    @FunctionalInterface
    private interface ToolInvocation {

        String call(String restoredToolInput);
    }
}
