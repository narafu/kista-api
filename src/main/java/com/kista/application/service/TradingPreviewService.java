package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.in.GetNextOrdersUseCase.SkipReason;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TradingPreviewService implements GetNextOrdersUseCase {

    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;
    private final KisPricePort kisPricePort;
    private final PrivacyTradePort privacyTradePort;
    private final TradingBalanceLoader balanceLoader;
    private final CycleOrderStrategies cycleStrategies;

    // execute()와 동일한 잔고 출처(TradingCycleHistory) 및 전략 분기(switch)로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Override
    @Transactional(readOnly = true)
    public Result preview(UUID accountId, UUID requesterId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);

        TradingCycle cycle = cyclePort.findByAccountId(accountId).stream()
                .filter(c -> c.status() == TradingCycle.Status.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("활성 거래 사이클이 없습니다: " + accountId));

        // 스케줄러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = LocalTime.now().isBefore(LocalTime.of(4, 0))
                ? LocalDate.now()
                : LocalDate.now().plusDays(1);

        // 잔고 로드 (preview 전용 — 이력 없음도 정상 skip으로 처리)
        TradingBalanceLoader.BalanceLoad load = balanceLoader.tryLoadBalance(cycle);
        if (load.isSkip()) {
            return new Result(today, null, List.of(), load.skipReason());
        }
        AccountBalance balance = load.balance();

        // INFINITE은 현재가 필요, PRIVACY는 기준매매표 필요 — 전략 입력 컨텍스트로 통합
        BigDecimal price = cycle.type() == TradingCycle.Type.INFINITE
                ? kisPricePort.getPrice(cycle.ticker(), account)
                : null;
        PrivacyTradeBase privacyBase = cycle.type() == TradingCycle.Type.PRIVACY
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        CycleOrderStrategy strategy = cycleStrategies.of(cycle);
        var planOpt = strategy.plan(new CycleOrderStrategy.PlanContext(
                balance, cycle, price, today, privacyBase, "preview:" + accountId));

        // 전략 차원 skip — 현재 케이스는 PRIVACY 기준매매표 미수신만 해당
        if (planOpt.isEmpty()) {
            return new Result(today, null, List.of(), SkipReason.NO_PRIVACY_BASE);
        }

        CycleOrderStrategy.OrderPlan plan = planOpt.get();
        List<Order> orders = plan.orders();
        // 주문 유효성: 매수금액 > 잔액 or 매도수량 > 보유수량이면 skip
        // position 포함 — 단위금액·현재가 정보를 프론트에 전달하기 위해 (INFINITE만 non-null)
        if (!balance.isOrderValid(orders)) {
            return new Result(today, plan.position(), List.of(), SkipReason.INSUFFICIENT_BALANCE);
        }
        return new Result(today, plan.position(), orders, null);
    }
}
