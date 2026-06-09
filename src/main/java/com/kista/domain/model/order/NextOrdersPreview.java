package com.kista.domain.model.order;

import com.kista.domain.model.strategy.InfinitePosition;

import java.time.LocalDate;
import java.util.List;

public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,   // PRIVACY/skip 시 null
        List<Order> orders,          // skip 시 빈 리스트
        SkipReason skipReason        // 정상이면 null
) {
    public enum SkipReason {
        NO_CYCLE_HISTORY,       // 사이클 이력 없음 (신규)
        INSUFFICIENT_BALANCE,   // 매수금액 > 잔액 or 매도수량 > 보유수량
        NO_PRIVACY_BASE         // PRIVACY 기준매매표 미수신
    }
}
