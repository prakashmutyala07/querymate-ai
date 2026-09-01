package com.ai.querymateai.mcp;

import java.util.Arrays;
import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.ai.querymateai.security.SensitiveDataGuard;

/**
 * Owns the DAB MCP tool callbacks.
 *
 * <p>The provider is built here rather than taken from autoconfiguration on purpose.
 * {@code spring.ai.mcp.client.toolcallback.enabled} is set to {@code false} so that the raw,
 * un-redacted callbacks are never registered as model-level default tools — if they were, the
 * model would execute those instead of the {@link SensitiveDataGuard}-wrapped copies passed per
 * request, and sensitive values would bypass tokenization entirely.
 */
@Service
public class McpToolCatalog {

    private final ObjectProvider<List<McpSyncClient>> mcpSyncClients;

    private volatile SyncMcpToolCallbackProvider provider;

    public McpToolCatalog(ObjectProvider<List<McpSyncClient>> mcpSyncClients) {
        this.mcpSyncClients = mcpSyncClients;
    }

    /** Tool callbacks, or an empty array when no MCP client is configured. */
    public ToolCallback[] toolCallbacksOrEmpty() {
        List<McpSyncClient> clients = clients();
        if (clients.isEmpty()) {
            return new ToolCallback[0];
        }
        SyncMcpToolCallbackProvider cached = this.provider;
        if (cached == null) {
            synchronized (this) {
                if (this.provider == null) {
                    this.provider = SyncMcpToolCallbackProvider.builder()
                            .mcpClients(clients)
                            .build();
                }
                cached = this.provider;
            }
        }
        return cached.getToolCallbacks();
    }

    public List<ToolSummary> tools() {
        return Arrays.stream(toolCallbacksOrEmpty())
                .map(ToolCallback::getToolDefinition)
                .map(ToolSummary::from)
                .toList();
    }

    private List<McpSyncClient> clients() {
        List<McpSyncClient> clients = this.mcpSyncClients.getIfAvailable();
        return clients == null ? List.of() : clients;
    }

    public record ToolSummary(String name, String description, String inputSchema) {

        static ToolSummary from(ToolDefinition definition) {
            return new ToolSummary(definition.name(), definition.description(), definition.inputSchema());
        }
    }
}
