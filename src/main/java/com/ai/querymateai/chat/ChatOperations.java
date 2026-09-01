package com.ai.querymateai.chat;

import java.util.List;

import com.ai.querymateai.mcp.McpToolCatalog;

public interface ChatOperations {

    ChatResponse chat(String message, String conversationId);

    ChatResponse chat(String message, String conversationId, ProgressSink progressSink);

    void clearMemory(String conversationId);

    List<McpToolCatalog.ToolSummary> tools();
}
