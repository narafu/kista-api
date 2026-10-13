package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

// DCA 등록·수정 요청 본문 — conditions는 단계 ①에서 빈 배열만 허용
public record DcaConfigRequest(
        @NotNull OrderDirection direction,  // 매수/매도
        @NotNull BigDecimal amountUsd,      // 회차당 금액 (USD)
        @NotNull OrderType orderType,       // MOO | MOC
        @NotNull DcaFrequency frequency,    // 주기
        DayOfWeek dayOfWeek,                // WEEKLY 전용
        Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        List<Map<String, Object>> conditions // 단계 ②에서 타입화 — 지금은 비어 있어야 함
) {
    public DcaConfig toDomain() {
        if (conditions != null && !conditions.isEmpty()) {
            throw new IllegalArgumentException("DCA 추가 조건은 아직 지원하지 않습니다.");
        }
        return new DcaConfig(direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth);
    }
}
