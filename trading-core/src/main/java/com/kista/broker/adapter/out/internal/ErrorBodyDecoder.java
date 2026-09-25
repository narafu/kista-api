package com.kista.broker.adapter.out.internal;

import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

// KIS/Toss 오류 응답 바디에서 분류용 필드를 디코딩하는 공용 기법 — substring .contains() 매칭 대신
// 브로커별 작은 record로 JSON 디코딩한다. 실제 오류 코드 값(EGW00201/already-filled 등)은 각 어댑터
// 소유로 남는다 — 이 클래스는 "디코딩해서 비교한다"는 기법만 공유한다(ClosingPriceLoop와 동일 취지).
public final class ErrorBodyDecoder {

    private ErrorBodyDecoder() {
    }

    public static <T> Optional<T> decode(ObjectMapper objectMapper, String body, Class<T> type) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(objectMapper.readValue(body, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
