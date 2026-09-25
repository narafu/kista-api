package com.kista.platform.internalapi;

import org.springframework.web.client.RestClient;

import java.util.NoSuchElementException;

// admin 내부 API 어댑터(TradingCommandHttpAdapter/PrivacyQueryHttpAdapter/TradingQueryHttpAdapter) 3곳이
// 반복하던 "HTTP 404 → NoSuchElementException / HTTP 400 → IllegalArgumentException" onStatus 매핑을
// 공용화한 순수 유틸 — RestClient.ResponseSpec 체인에 이어붙여 쓴다. platform은 outbound-zero 인프라
// leaf라 admin 등 다른 com.kista 모듈 타입을 참조하지 않고 HTTP 상태코드만 다룬다.
// 422/429/409 등 도메인 전용 표지 예외로 갈라지는 케이스는 호출부에 그대로 남긴다.
public final class InternalApiStatusHandlers {

    private InternalApiStatusHandlers() {}

    // 404 응답을 NoSuchElementException으로 변환 — 응답 바디 detail 필드가 있으면 그 메시지, 없으면 fallback
    public static RestClient.ResponseSpec notFoundAsNoSuchElement(RestClient.ResponseSpec spec, String fallback) {
        return spec.onStatus(status -> status.value() == 404, (request, response) -> {
            throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, fallback));
        });
    }

    // 400 응답을 IllegalArgumentException으로 변환 — 응답 바디 detail 필드가 있으면 그 메시지, 없으면 fallback
    public static RestClient.ResponseSpec badRequestAsIllegalArgument(RestClient.ResponseSpec spec, String fallback) {
        return spec.onStatus(status -> status.value() == 400, (request, response) -> {
            throw new IllegalArgumentException(InternalApiErrorDetails.detailOrDefault(response, fallback));
        });
    }
}
