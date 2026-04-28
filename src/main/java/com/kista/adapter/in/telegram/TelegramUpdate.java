package com.kista.adapter.in.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUpdate(
        @JsonProperty("update_id") Long updateId,
        @JsonProperty("message") Message message
) {
    public record Message(
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("chat") Chat chat,
            @JsonProperty("text") String text
    ) {}

    public record Chat(
            @JsonProperty("id") Long id
    ) {}
}
