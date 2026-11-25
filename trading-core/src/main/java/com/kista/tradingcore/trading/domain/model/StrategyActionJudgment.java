package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.trading.domain.model.StrategyAction.BlockReason;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 전략 행위 가능 여부 판정 — 응답 조립 시 이미 로드된 StrategyDetail만 쓴다(추가 조회 없음).
// 불변식: allowed ∪ blocked = 종류가 지원하는 행위 전체, allowed ∩ blocked = ∅, 목록 순서는 enum 선언순.
// 판정하지 않는 거절(조회가 더 필요): 마감 리포트 창(시드 수정·투자금 추가·인출), 보관 전 당일 접수 주문 마감 반영, 재개 시 남은 원장 < 1주 가격 — 서비스가 요청 시 거절한다
public record StrategyActionJudgment(
        List<StrategyAction> allowed, // 지금 가능한 행위
        List<Blocked> blocked         // 지원하지만 지금 막힌 행위와 사유
) {
    // 막힌 행위 하나 — 사유는 항상 있다
    public record Blocked(StrategyAction action, BlockReason reason) {}

    public static StrategyActionJudgment of(StrategyDetail detail) {
        List<StrategyAction> allowed = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        for (StrategyAction action : StrategyAction.values()) {
            // 종류가 지원하지 않는 행위는 어느 쪽에도 싣지 않는다
            if (!action.supports(detail.strategy().type())) continue;
            BlockReason reason = blockReason(action, detail);
            if (reason == null) allowed.add(action);
            else blocked.add(new Blocked(action, reason));
        }
        return new StrategyActionJudgment(List.copyOf(allowed), List.copyOf(blocked));
    }

    // 서비스 검사 순서대로 첫 거절 사유 — 보관 검사가 각 서비스에서 가장 먼저다(삭제는 상태 사전 검사 없음)
    private static BlockReason blockReason(StrategyAction action, StrategyDetail detail) {
        Strategy strategy = detail.strategy();
        if (action == StrategyAction.DELETE) return null;
        if (strategy.isArchived()) return BlockReason.ARCHIVED;
        return switch (action) {
            case PAUSE -> strategy.isPaused() ? BlockReason.ALREADY_PAUSED : null;
            case RESUME -> strategy.isActive() ? BlockReason.ALREADY_ACTIVE : null;
            case ARCHIVE -> hasHoldings(detail) ? BlockReason.HAS_HOLDINGS : null;
            case EDIT_SEED -> !detail.cycleInProgress() ? BlockReason.NO_ACTIVE_CYCLE
                    : hasHoldings(detail) ? BlockReason.HAS_HOLDINGS : null;
            case INJECT_CAPITAL -> !detail.cycleInProgress() ? BlockReason.NO_ACTIVE_CYCLE : null;
            case WITHDRAW_CAPITAL -> !detail.cycleInProgress() ? BlockReason.NO_ACTIVE_CYCLE
                    : noWithdrawableBalance(detail.ledgerDeposit()) ? BlockReason.NO_WITHDRAWABLE_BALANCE : null;
            case RESET_VR, EDIT_DCA_CONFIG, DELETE -> null;
        };
    }

    // 포지션 없음(null)은 보유 0으로 본다 — 보관 서비스의 "포지션 없음은 통과"와 같은 기준
    private static boolean hasHoldings(StrategyDetail detail) {
        return detail.currentHoldings() != null && detail.currentHoldings() != 0;
    }

    // 인출 가능액 ≤ 장부 예수금이라 예수금 0 이하면 어떤 금액도 거절된다. 값이 없으면(포지션 없음) 판정하지 않는다
    private static boolean noWithdrawableBalance(BigDecimal ledgerDeposit) {
        return ledgerDeposit != null && ledgerDeposit.signum() <= 0;
    }
}
