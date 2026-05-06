package com.kista.adapter.in.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUpdate(
        @JsonProperty("update_id") Long updateId,
        @JsonProperty("message") Message message,
        @JsonProperty("callback_query") CallbackQuery callbackQuery // 인라인 버튼 클릭 시 수신
) {
    public record Message(
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("chat") Chat chat,
            @JsonProperty("text") String text
    ) {}

    public record Chat(
            @JsonProperty("id") Long id
    ) {}

    // 인라인 키보드 버튼 클릭 이벤트
    public record CallbackQuery(
            @JsonProperty("id") String id,           // answerCallbackQuery에 필요한 ID
            @JsonProperty("data") String data,        // callback_data (예: "approve:uuid")
            @JsonProperty("message") Message message  // 버튼이 포함된 원본 메시지
    ) {}
}
