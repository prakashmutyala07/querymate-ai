package com.ai.querymateai.chat;

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
        ChatResponse response = ChatResponse.from("demo", "model", false, answer, true, ChatResponse.UNKNOWN_TOTAL);

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

        ChatResponse response = ChatResponse.from("conversation-1", "primary", true, answer, true, ChatResponse.UNKNOWN_TOTAL);

        assertThat(response).isEqualTo(new ChatResponse("conversation-1", "primary", true,
                ChatResponse.Status.PARTIAL, "Two orders were found.", List.of("OrderId", "Total"),
                List.of(List.of("17", "49.95"), List.of("18", "12.00")), true, true,
                ChatResponse.UNKNOWN_TOTAL, "One page was unavailable.", "Retry the missing page?"));
    }

    @Test
    void partialResultsIsForcedWhenMoreRecordsMatchThanAreShown() {
        ChatResponse.ModelAnswer answer = new ChatResponse.ModelAnswer(ChatResponse.Status.ANSWER,
                "Showing the ten most recent orders.", List.of("OrderId"),
                List.of(List.of("1"), List.of("2")), false, "", "");

        ChatResponse response = ChatResponse.from("c", "m", false, answer, true, 120L);

        assertThat(response.totalCount()).isEqualTo(120L);
        assertThat(response.partialResults()).isTrue();
        assertThat(response.status()).isEqualTo(ChatResponse.Status.PARTIAL);
    }

    @Test
    void countOnlyAnswerWithNoRowsIsNotMarkedPartial() {
        ChatResponse.ModelAnswer answer = new ChatResponse.ModelAnswer(ChatResponse.Status.ANSWER,
                "There are 120 orders.", List.of(), List.of(), false, "", "");

        ChatResponse response = ChatResponse.from("c", "m", false, answer, true, 120L);

        assertThat(response.totalCount()).isEqualTo(120L);
        assertThat(response.partialResults()).isFalse();
    }

    @Test
    void unknownTotalLeavesTheModelsOwnPartialFlagAlone() {
        ChatResponse.ModelAnswer answer = new ChatResponse.ModelAnswer(ChatResponse.Status.ANSWER, "Two orders.",
                List.of("OrderId"), List.of(List.of("1"), List.of("2")), false, "", "");

        ChatResponse response = ChatResponse.from("c", "m", false, answer, true, ChatResponse.UNKNOWN_TOTAL);

        assertThat(response.totalCount()).isEqualTo(ChatResponse.UNKNOWN_TOTAL);
        assertThat(response.partialResults()).isFalse();
    }
}
