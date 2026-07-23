package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.application.service.broker.BrokerCallGuard;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

// 전략 1건의 오늘자 주문 계획 계산 오케스트레이션 — 잔고 로드 → prevClose(필요 시) → privacyBase(필요 시) → compute()
// TradingPreviewService(대상 전략 1건)와 TradingBuyCompetitionSimulator(경쟁 전략 N건 가상 계산)가 공유
// package-private — application/service/trading 패키지 전용
@Component
@RequiredArgsConstructor
class StrategyOrderPlanBuilder {

    private final TradingBalanceLoader balanceLoader;       // cycle_position 최신 스냅샷 기반 잔고 로드
    private final BrokerAdapterRegistry registry;            // 전일종가 조회용 브로커 라우팅
    private final PrivacyTradePort privacyTradePort;         // PRIVACY 기준매매표 조회
    private final CycleOrderComputer orderComputer;          // 전략 계산 + 유효성 검증
    private final CycleOrderStrategies cycleOrderStrategies; // 전략 타입별 capability 조회

    // 계산 결과 — 정상이면 plan non-null, skip이면 skipReason non-null
    record PlanResult(CycleOrderStrategy.OrderPlan plan, SkipReason skipReason) {
        boolean isSkip() {
            return plan == null;
        }
    }

    PlanResult build(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today, String label) {
        return build(strategy, account, currentCycle, today, label, null);
    }

    // prevCloseCache: 배치 미리보기(TradingPreviewService.previewBatch) 전용 — 계좌 내 종목별 전일종가를
    // 1회 일괄 조회(getPrevCloses)해 넘기면 전략마다 개별 KIS 호출을 생략한다. 캐시에 없는 종목은
    // 기존과 동일하게 단건 라이브 조회로 폴백한다.
    PlanResult build(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today, String label,
                      Map<Strategy.Ticker, BigDecimal> prevCloseCache) {
        // 잔고 이력 없으면 계산 자체가 불가능한 skip
        TradingBalanceLoader.BalanceLoad load = balanceLoader.tryLoadBalance(strategy);
        if (load.isSkip()) {
            return new PlanResult(null, load.skipReason());
        }
        AccountBalance balance = load.balance();

        CycleOrderStrategy orderStrategy = cycleOrderStrategies.of(strategy);
        BigDecimal prevClosePrice = null;
        if (orderStrategy.requiresPrevClose()) {
            prevClosePrice = prevCloseCache != null && prevCloseCache.containsKey(strategy.ticker())
                    ? prevCloseCache.get(strategy.ticker())
                    : BrokerCallGuard.wrap("전일종가 조회",
                            () -> registry.require(account, BrokerPricePort.class).getPrevClose(strategy.ticker(), account));
        }
        PrivacyTradeBase privacyBase = privacyTradePort.findBaseIfPrivacy(strategy, today);

        CycleOrderStrategy.OrderPlan plan = orderComputer.compute(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, label, null)
                .orElse(null);
        if (plan == null) {
            return new PlanResult(null, SkipReason.NO_PRIVACY_BASE);
        }
        return new PlanResult(plan, null);
    }
}
