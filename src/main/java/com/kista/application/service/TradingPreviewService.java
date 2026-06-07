package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.in.GetNextOrdersUseCase.SkipReason;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;

@Service
@RequiredArgsConstructor
class TradingPreviewService implements GetNextOrdersUseCase {

    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;
    private final KisPricePort kisPricePort;
    private final PrivacyTradePort privacyTradePort;
    private final TradingBalanceLoader balanceLoader;
    private final TradingOrderPlanner orderPlanner;

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

        return switch (cycle.type()) {
            case INFINITE -> {
                BigDecimal price = kisPricePort.getPrice(cycle.ticker(), account);
                TradingOrderPlanner.InfiniteCalc calc = orderPlanner.calcInfinite(balance, cycle, price, today, "preview:" + accountId);
                List<Order> orders = calc.orders();
                // 주문 유효성: 매수금액 > 잔액 or 매도수량 > 보유수량이면 skip
                if (!balance.isOrderValid(orders)) {
                    // position 포함 — 단위금액·현재가 정보를 프론트에 전달하기 위해
                    yield new Result(today, calc.position(), List.of(), SkipReason.INSUFFICIENT_BALANCE);
                }
                yield new Result(today, calc.position(), orders, null);
            }
            case PRIVACY -> {
                // 스케줄러 planAndSaveOrders와 동일: 기준매매표 없으면 skip
                PrivacyTradeBase base = privacyTradePort.findTodayTrade(today).orElse(null);
                if (base == null) {
                    yield new Result(today, null, List.of(), SkipReason.NO_PRIVACY_BASE);
                }
                List<Order> orders = orderPlanner.calcPrivacy(balance, cycle.initialUsdDeposit(), base);
                yield new Result(today, null, orders, null);
            }
        };
    }
}
