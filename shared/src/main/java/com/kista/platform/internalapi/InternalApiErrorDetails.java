package com.kista.platform.internalapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpResponse;

// 내부 API(:api ↔ :trading-core) 호출 어댑터 4종(TradingCommandHttpAdapter/TradingQueryHttpAdapter/
// InvestmentPointsHttpAdapter 등)이 공유하는 에러 상세 추출 헬퍼 — trading 쪽 GlobalExceptionHandler가
// ProblemDetail(RFC 7807) 응답 바디에 실은 "detail" 필드를 읽어 고정 문구 대신 원 메시지를 되살린다.
// platform은 어느 com.kista 모듈도 참조하지 않는 인프라 leaf라 :api/:trading-core 양쪽 어댑터가
// 안전하게 재사용할 수 있다.
public final class InternalApiErrorDetails {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InternalApiErrorDetails() {}

    // 바디가 JSON이 아니거나 detail 필드가 없으면 fallback 고정 문구로 폴백
    public static String detailOrDefault(ClientHttpResponse response, String fallback) {
        try {
            JsonNode node = MAPPER.readTree(response.getBody());
            JsonNode detail = node.get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isBlank()) {
                return detail.asText();
            }
        } catch (Exception e) {
            // 바디 파싱 실패 — 고정 문구로 폴백
        }
        return fallback;
    }
}
