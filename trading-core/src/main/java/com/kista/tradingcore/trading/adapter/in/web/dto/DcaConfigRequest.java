package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;

// DCA 등록·수정 요청 본문 — amount/amountUsd는 한 배포 사이클 동안 이중 계약(UI 전환 후 amountUsd 제거는 별도 작업)
public record DcaConfigRequest(
        @NotNull OrderDirection direction,  // 매수/매도
        @Schema(description = "회차당 금액 (계좌 통화 — 주식 USD, 코인 KRW 원 단위 정수). amount·amountUsd 중 하나 필수")
        BigDecimal amount,                  // 회차당 금액 (계좌 통화)
        @Schema(description = "회차당 금액 (구 필드, amount와 같은 의미 — 둘 다 보내면 같은 값이어야 함)", deprecated = true)
        BigDecimal amountUsd,               // 구 필드 — 전환 기간 호환
        @NotNull OrderType orderType,       // 주식 MOO | MOC, 코인 MARKET
        @NotNull DcaFrequency frequency,    // 주기
        DayOfWeek dayOfWeek,                // WEEKLY 전용
        Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        List<DcaConditionRequest> conditions, // 추가 조건(AND) — null이면 빈 목록
        @Schema(description = "코인 실행 시각 (KST 0~23, 코인 필수). 주식 주문유형이면 무시")
        Integer executionHour               // 코인 전용 실행 시각
) {
    public DcaConfig toDomain() {
        if (conditions != null && conditions.contains(null)) {
            throw new IllegalArgumentException("조건 항목이 비어 있습니다.");
        }
        List<DcaCondition> domainConditions = conditions == null ? List.of()
                : conditions.stream().map(DcaConditionRequest::toDomain).toList();
        // 주식 주문유형이면 실행 시각을 저장하지 않는다(무시)
        Integer hour = orderType == OrderType.MARKET ? executionHour : null;
        return new DcaConfig(direction, resolveAmount(), orderType, frequency, dayOfWeek, dayOfMonth, domainConditions, hour);
    }

    // 두 필드 중 하나 — 둘 다 오면 값(compareTo)이 같아야 한다(300과 300.00은 같은 값)
    private BigDecimal resolveAmount() {
        if (amount == null && amountUsd == null) throw new IllegalArgumentException("amount는 필수입니다.");
        if (amount != null && amountUsd != null && amount.compareTo(amountUsd) != 0) {
            throw new IllegalArgumentException("amount와 amountUsd 값이 다릅니다.");
        }
        return amount != null ? amount : amountUsd;
    }
}
