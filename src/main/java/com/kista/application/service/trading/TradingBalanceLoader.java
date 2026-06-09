package com.kista.application.service.trading;

import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCyclePosition;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.port.out.TradingCyclePositionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// package-private — application/service 패키지 전용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingBalanceLoader {

    private final TradingCyclePositionPort cycleHistoryPort;

    // 잔고 로드 결과 — 정상이면 balance non-null, skip이면 skipReason non-null
    record BalanceLoad(AccountBalance balance, SkipReason skipReason) {
        boolean isSkip() {
            return skipReason != null;
        }
    }

    // 잔고 로드 — preview용: 이력 없음은 skip, 있으면 그대로 반환
    BalanceLoad tryLoadBalance(TradingCycle cycle) {
        return cycleHistoryPort.findRecentByCycleId(cycle.id(), 1).stream()
                .findFirst()
                .map(h -> new BalanceLoad(new AccountBalance(h.holdings(), h.avgPrice(), h.usdDeposit()), null))
                .orElse(new BalanceLoad(null, SkipReason.NO_CYCLE_HISTORY));
    }

    // 잔고 로드 — execute용: 이력 없음은 데이터 무결성 오류 → IllegalStateException
    BalanceLoad loadBalanceOrThrow(TradingCycle cycle) {
        TradingCyclePosition latest = cycleHistoryPort.findRecentByCycleId(cycle.id(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("사이클 이력 없음: cycleId=" + cycle.id()));
        return new BalanceLoad(new AccountBalance(latest.holdings(), latest.avgPrice(), latest.usdDeposit()), null);
    }
}
