package com.kista.api.web.dto;

import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;

// 증권사 메타 — EnumMeta 3필드(code/label/description=약칭)에 시장·통화를 더한다. 시장·통화는 enum name 문자열(스키마 이름 충돌 방지)
public record BrokerMeta(
        @Schema(description = "enum name() 값", example = "UPBIT")
        String code,        // enum name() 값
        @Schema(description = "한국어 표시 이름")
        String label,       // 한국어 표시 이름
        @Schema(description = "UI 모바일 약칭")
        String description, // UI 모바일 약칭 (기존 EnumMeta.description 자리 유지)
        @Schema(description = "거래 시장", example = "CRYPTO_KRW")
        String market,      // Market name()
        @Schema(description = "계좌 기준 통화", example = "KRW")
        String currency     // Currency name()
) {
    public static BrokerMeta from(Broker b) {
        return new BrokerMeta(b.name(), b.getLabel(), b.getShortLabel(), b.getMarket().name(), b.getCurrency().name());
    }
}
