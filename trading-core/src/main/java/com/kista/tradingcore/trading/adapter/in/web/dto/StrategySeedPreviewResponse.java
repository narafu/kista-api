package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.StrategySeedPreview;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

// 전략 등록/수정 폼의 최소시드·기준가 미리보기 응답
// skipReason: 계산 불가 사유 (예: "NO_PRIVACY_BASE" — 기준매매표 미수신). 정상이면 null
public record StrategySeedPreviewResponse(
        @Schema(description = "거래 종목", example = "SOXL")
        String ticker,
        @Schema(description = "기준가 (계산 불가 시 null)")
        BigDecimal basePrice,
        @Schema(description = "최소 필요 시드 (계산 불가 시 null)")
        BigDecimal minSeed,
        @Schema(description = "계산 불가 사유 (정상이면 null)", example = "NO_PRIVACY_BASE")
        String skipReason,
        @Schema(description = "가용 예수금 = 증권사 매수가능금액 − 다른 전략 원장 합 (모의계좌·조회 실패면 null). 입력 시드가 넘으면 경고만 한다")
        BigDecimal availableCash
) {
    public static StrategySeedPreviewResponse from(StrategySeedPreview preview) {
        return new StrategySeedPreviewResponse(
                preview.ticker(), preview.basePrice(), preview.minSeed(), preview.skipReason(),
                preview.availableCash());
    }
}
