package com.ai.querymateai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.ai.querymateai.chat.ChatOperations;
import com.ai.querymateai.chat.ChatResponse;
import com.ai.querymateai.chat.ChatResponse.Status;
import com.ai.querymateai.chat.ProgressSink;
import com.ai.querymateai.mcp.McpToolCatalog;

class ChatControllerTests {

    private static final int TEST_MAX_MESSAGE_CHARS = 80;

    private final FakeAiChatOperations aiChatService = new FakeAiChatOperations();

    private final ChatController controller = new ChatController(this.aiChatService,
            new SameThreadAsyncTaskExecutor(), TEST_MAX_MESSAGE_CHARS);

    @Test
    void chatAcceptsValidNormalRequest() {
        this.controller.chat(new ChatController.ChatRequest("List entities", "demo"));

        assertThat(this.aiChatService.chatRequests).isEqualTo(1);
        assertThat(this.aiChatService.lastMessage).isEqualTo("List entities");
    }

    @Test
    void chatRejectsWhitespaceOnlyMessage() {
        ChatController.ChatRequest request = new ChatController.ChatRequest(" ", "demo");

        assertThatThrownBy(() -> this.controller.chat(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Message must not be blank.")
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.aiChatService.chatRequests).isZero();
    }

    @Test
    void chatRejectsEmptyMessage() {
        ChatController.ChatRequest request = new ChatController.ChatRequest("", "demo");

        assertThatThrownBy(() -> this.controller.chat(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Message must not be blank.")
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.aiChatService.chatRequests).isZero();
    }

    @Test
    void chatRejectsMissingMessage() {
        ChatController.ChatRequest request = new ChatController.ChatRequest(null, "demo");

        assertThatThrownBy(() -> this.controller.chat(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Message must not be blank.")
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.aiChatService.chatRequests).isZero();
    }

    @Test
    void chatRejectsMissingRequestBody() {
        assertThatThrownBy(() -> this.controller.chat(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Request body is required.")
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.aiChatService.chatRequests).isZero();
    }

    @Test
    void chatAcceptsMessageAtMaximumLength() {
        String message = "x".repeat(TEST_MAX_MESSAGE_CHARS);

        this.controller.chat(new ChatController.ChatRequest(message, "demo"));

        assertThat(this.aiChatService.chatRequests).isEqualTo(1);
        assertThat(this.aiChatService.lastMessage).isEqualTo(message);
    }

    @Test
    void chatRejectsMessageExceedingMaximumLength() {
        ChatController.ChatRequest request = new ChatController.ChatRequest(
                "x".repeat(TEST_MAX_MESSAGE_CHARS + 1), "demo");

        assertThatThrownBy(() -> this.controller.chat(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Message exceeds the maximum allowed length.")
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.aiChatService.chatRequests).isZero();
    }

    @Test
    void chatAcceptsComplexNaturalLanguageDatabaseQuestion() {
        String question = "Top 5 orders by revenue, grouped by region?";

        this.controller.chat(new ChatController.ChatRequest(question, "demo"));

        assertThat(this.aiChatService.chatRequests).isEqualTo(1);
        assertThat(this.aiChatService.lastMessage).isEqualTo(question);
    }

    @Test
    void chatRejectsUnsafeConversationId() {
        ChatController.ChatRequest request = new ChatController.ChatRequest("List entities", "../../other-user");

        assertThatThrownBy(() -> this.controller.chat(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.aiChatService.chatRequests).isZero();
    }

    @Test
    void clearMemoryRejectsInvalidConversationId() {
        assertThatThrownBy(() -> this.controller.clearMemory("bad id/with/slashes"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid conversationId")
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void chatMapsStructuredServiceResponse() {
        this.aiChatService.chatResponse = new ChatResponse("demo", "gpt-4.1-mini", false, Status.ANSWER,
                "There are 4 entities.", List.of("Entity"), List.of(List.of("Customer"), List.of("Order")), true,
                false, "metadata only", "");

        ChatResponse response = this.controller
                .chat(new ChatController.ChatRequest("List entities", "demo"))
                .getBody();

        assertThat(response).isNotNull();
        assertThat(response.conversationId()).isEqualTo("demo");
        assertThat(response.model()).isEqualTo("gpt-4.1-mini");
        assertThat(response.columns()).containsExactly("Entity");
        assertThat(response.rows()).hasSize(2);
        assertThat(response.message()).isEqualTo("There are 4 entities.");
        assertThat(response.usedDatabaseTools()).isTrue();
        assertThat(response.partialResults()).isFalse();
    }

    @Test
    void mcpToolsDelegatesToCatalog() {
        List<McpToolCatalog.ToolSummary> tools = List.of(new McpToolCatalog.ToolSummary("describe_entities",
                "Lists all entities", "{}"));
        this.aiChatService.tools = tools;

        assertThat(this.controller.mcpTools()).isEqualTo(tools);
    }

    @Test
    void clearMemoryDelegatesToService() {
        this.controller.clearMemory("demo");

        assertThat(this.aiChatService.clearedConversations).containsExactly("demo");
    }

    static class SameThreadAsyncTaskExecutor implements AsyncTaskExecutor {

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    static class FakeAiChatOperations implements ChatOperations {

        private ChatResponse chatResponse = new ChatResponse("demo", "model", false, Status.ANSWER, "", List.of(),
                List.of(), false, false, "", "");

        private final List<String> clearedConversations = new ArrayList<>();

        private int chatRequests;

        private String lastMessage;

        @Override
        public ChatResponse chat(String message, String conversationId) {
            this.chatRequests++;
            this.lastMessage = message;
            return this.chatResponse;
        }

        @Override
        public ChatResponse chat(String message, String conversationId, ProgressSink progressSink) {
            this.chatRequests++;
            this.lastMessage = message;
            return this.chatResponse;
        }

        @Override
        public void clearMemory(String conversationId) {
            this.clearedConversations.add(conversationId);
        }

        private List<McpToolCatalog.ToolSummary> tools = List.of();

        @Override
        public List<McpToolCatalog.ToolSummary> tools() {
            return this.tools;
        }
    }
}
