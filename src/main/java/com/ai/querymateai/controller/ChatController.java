package com.ai.querymateai.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ai.querymateai.chat.ChatOperations;
import com.ai.querymateai.chat.ChatResponse;
import com.ai.querymateai.mcp.McpToolCatalog;

@RestController
@RequestMapping("/api")
public class ChatController {

    /** Covers the default primary call, retry, fallback, and a small delivery margin. */
    private static final long SSE_TIMEOUT_MILLIS = 390_000L;

    private static final int DEFAULT_MAX_MESSAGE_CHARS = 8_000;

    private static final java.util.regex.Pattern CONVERSATION_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final ChatOperations chatCoordinator;

    private final AsyncTaskExecutor chatTaskExecutor;

    private final int maxMessageChars;

    public ChatController(ChatOperations chatCoordinator, AsyncTaskExecutor chatTaskExecutor,
            @Value("${app.chat.max-message-chars:" + DEFAULT_MAX_MESSAGE_CHARS + "}") int maxMessageChars) {
        this.chatCoordinator = chatCoordinator;
        this.chatTaskExecutor = chatTaskExecutor;
        this.maxMessageChars = maxMessageChars > 0 ? maxMessageChars : DEFAULT_MAX_MESSAGE_CHARS;
    }

    @GetMapping("/mcp/tools")
    public List<McpToolCatalog.ToolSummary> mcpTools() {
        return this.chatCoordinator.tools();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        validate(request, this.maxMessageChars);

        return ResponseEntity.ok(this.chatCoordinator.chat(request.message(), request.conversationId()));
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        validate(request, this.maxMessageChars);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        this.chatTaskExecutor.execute(() -> {
            try {
                send(emitter, "progress", new ProgressEvent("accepted", "Request accepted."));
                ChatResponse result = this.chatCoordinator.chat(request.message(), request.conversationId(),
                        (stage, message) -> send(emitter, "progress", new ProgressEvent(stage, message)));
                send(emitter, "complete", result);
                emitter.complete();
            }
            catch (RuntimeException ex) {
                try {
                    send(emitter, "error", new ProgressEvent("error", errorMessage(ex)));
                }
                finally {
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    @DeleteMapping("/conversations/{conversationId}/memory")
    public ResponseEntity<Void> clearMemory(@PathVariable String conversationId) {
        validateConversationId(conversationId);
        this.chatCoordinator.clearMemory(conversationId);
        return ResponseEntity.noContent().build();
    }

    public record ChatRequest(String message, String conversationId) {
    }

    public record ProgressEvent(String stage, String message) {
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        }
        catch (IOException ex) {
            throw new IllegalStateException("SSE client disconnected", ex);
        }
    }

    private static String errorMessage(RuntimeException ex) {
        if (ex instanceof ResponseStatusException statusException && StringUtils.hasText(statusException.getReason())) {
            return statusException.getReason();
        }
        return "Chat request failed.";
    }

    private static void validate(ChatRequest request, int maxMessageChars) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        if (!StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be blank.");
        }
        if (request.message().length() > maxMessageChars) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Message exceeds the maximum allowed length.");
        }
        if (StringUtils.hasText(request.conversationId())) {
            validateConversationId(request.conversationId());
        }
    }

    private static void validateConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId) || !CONVERSATION_ID.matcher(conversationId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid conversationId");
        }
    }
}
