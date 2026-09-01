package com.example.querymateai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

class ChatResponseTests {

    @Test
    void springAiConverterParsesTypedEmptyResponse() {
        BeanOutputConverter<ChatResponse.ModelAnswer> converter =
                new BeanOutputConverter<>(ChatResponse.ModelAnswer.class);

        ChatResponse.ModelAnswer answer = converter.convert("""
                {
                  "status": "EMPTY",
                  "answer": "No matching orders were found.",
                  "columns": [],
                  "rows": [],
                  "partialResults": false,
                  "dataNotes": "Calendar month scope.",
                  "followUpQuestion": ""
                }
                """);
        ChatResponse response = ChatResponse.from("demo", "model", false, answer, true);

        assertThat(response.status()).isEqualTo(ChatResponse.Status.EMPTY);
        assertThat(response.message()).isEqualTo("No matching orders were found.");
        assertThat(response.columns()).isEmpty();
        assertThat(response.usedDatabaseTools()).isTrue();
    }

    @Test
    void mapsEveryStructuredResponseFieldToTheEndpointContract() {
        ChatResponse.ModelAnswer answer = new ChatResponse.ModelAnswer(ChatResponse.Status.PARTIAL,
                "Two orders were found.", List.of("OrderId", "Total"),
                List.of(List.of("17", "49.95"), List.of("18", "12.00")), true,
                "One page was unavailable.", "Retry the missing page?");

        ChatResponse response = ChatResponse.from("conversation-1", "primary", true, answer, true);

        assertThat(response).isEqualTo(new ChatResponse("conversation-1", "primary", true,
                ChatResponse.Status.PARTIAL, "Two orders were found.", List.of("OrderId", "Total"),
                List.of(List.of("17", "49.95"), List.of("18", "12.00")), true, true,
                "One page was unavailable.", "Retry the missing page?"));
    }
}
