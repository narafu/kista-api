package com.kista.api.web.dto;

import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;

// 증권사 메타 — EnumMeta 3필드(code/label/description=약칭)에 통화를 더한다. 통화는 enum name 문자열(스키마 이름 충돌 방지)
public record BrokerMeta(
        @Schema(description = "enum name() 값", example = "MERITZ")
        String code,        // enum name() 값
        @Schema(description = "한국어 표시 이름")
        String label,       // 한국어 표시 이름
        @Schema(description = "UI 모바일 약칭")
        String description, // UI 모바일 약칭 (기존 EnumMeta.description 자리 유지)
        @Schema(description = "계좌 기준 통화", example = "USD")
        String currency,    // Currency name()
        @Schema(description = "API 키 만료일 출처 (NONE·ESTIMATED)", example = "ESTIMATED")
        String apiKeyExpirySource, // ApiKeyExpirySource name()
        @Schema(description = "신규 계좌 등록 허용 여부 (/api/runtime-config brokers[broker].enabled와 같은 값)")
        boolean enabled // 증권사 신규 등록 허용 여부
) {
    public static BrokerMeta from(Broker b, boolean enabled) {
        return new BrokerMeta(b.name(), b.getLabel(), b.getShortLabel(), b.getCurrency().name(), b.getApiKeyExpirySource().name(), enabled);
    }
}
