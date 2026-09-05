package com.ai.querymateai.security;

import java.util.List;

import com.ai.querymateai.chat.ChatResponse;

final class UiDisclosureService {

    private final RequestTokenVault vault;

    UiDisclosureService(RequestTokenVault vault) {
        this.vault = vault;
    }

    ChatResponse toUiResponse(ChatResponse response) {
        List<String> columns = response.columns();
        List<List<String>> rows = response.rows().stream()
                .map(row -> row.stream().map(value -> this.vault.displayProtectedValues(value, false)).toList())
                .toList();
        return new ChatResponse(response.conversationId(), response.model(), response.fallbackUsed(),
                response.status(), display(response.message()), columns, rows,
                response.usedDatabaseTools(), response.partialResults(), response.totalCount(),
                display(response.dataNotes()), display(response.followUpQuestion()));
    }

    private String display(String value) {
        return this.vault.displayProtectedValues(value, false);
    }
}
