package com.example.sqlmcpchatopenrouter.chat;

import java.util.List;

import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;

public interface ChatOperations {

    ChatResponse chat(String message, String conversationId);

    ChatResponse chat(String message, String conversationId, ProgressSink progressSink);

    void clearMemory(String conversationId);

    List<McpToolCatalog.ToolSummary> tools();
}
