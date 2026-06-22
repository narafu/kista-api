package com.kista.application.service.account;

import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCommissionRate;
import com.kista.domain.model.toss.TossSellableQuantity;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisSellableQuantityPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TosMarginPort;
import com.kista.domain.port.out.TossCommissionsPort;
import com.kista.domain.port.out.TossPortfolioPort;
import com.kista.domain.port.out.TossSellableQuantityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// account.broker() 기반으로 KIS/Toss 통계 포트 선택 — AccountStatisticsService의 if(isToss()) 분기 제거
@Slf4j
@Component
@RequiredArgsConstructor
class BrokerStatisticsRouter {

    private final KisPortfolioPort kisPortfolioPort;
    private final TossPortfolioPort tossPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final TosMarginPort tosMarginPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisSellableQuantityPort kisSellableQuantityPort;
    private final TossSellableQuantityPort tossSellableQuantityPort;
    private final TossCommissionsPort tossCommissionsPort;
    private final BrokerExecutionRouter brokerExecutionRouter;
    private final StrategyPort strategyPort;

    // 체결기준현재잔고 — KIS: CTRP6504R + TTTC2101R 보정 / Toss: 보유종목+예수금 직접 산출
    PresentBalanceResult getPresentBalance(Account account) {
        if (account.isToss()) return tossPortfolioPort.getPresentBalance(account);
        // KIS: CTRP6504R은 예수금·환율 미제공 → TTTC2101R(margin)로 보정
        PresentBalanceResult portfolio = kisPortfolioPort.getPresentBalance(account);
        List<MarginItem> margins = kisMarginPort.getMargin(account);
        BigDecimal usdDeposit = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::purchasableAmount)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal rate = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::usdToKrwRate)
                .findFirst().orElse(BigDecimal.ZERO);
        return new PresentBalanceResult(
                portfolio.items(), portfolio.totalAssetUsd(), portfolio.totalEvalProfit(),
                portfolio.totalReturnRate(), usdDeposit, rate
        );
    }

    // 증거금 통화별 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
    List<MarginItem> getMargin(Account account) {
        return account.isToss() ? tosMarginPort.getMarginItems(account) : kisMarginPort.getMargin(account);
    }

    // 판매 가능 수량 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
    TossSellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return account.isToss()
                ? tossSellableQuantityPort.getSellableQuantity(ticker, account)
                : kisSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    // 일별 거래내역 — KIS: CTOS4001R / Toss: execution+commission 조합 구성
    DailyTransactionResult getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to) {
        if (!account.isToss()) return kisDailyTransactionPort.getDailyTransactions(from, to, account);
        return buildTossDailyTransactions(accountId, account, from, to);
    }

    // Toss 체결 내역 + 수수료율로 DailyTransactionResult 조립
    private DailyTransactionResult buildTossDailyTransactions(UUID accountId, Account account,
                                                               LocalDate from, LocalDate to) {
        Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
        if (ticker.isEmpty()) {
            return new DailyTransactionResult(List.of(), emptySummary());
        }
        List<Execution> executions = brokerExecutionRouter.getExecutions(from, to, ticker.get(), account);

        // US 수수료율 조회 — 실패 시 0으로 처리 (수수료 미표시)
        BigDecimal usCommissionRate = tossCommissionsPort.getCommissions(account).stream()
                .filter(c -> "US".equals(c.marketCountry()))
                .map(TossCommissionRate::rate)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Toss US 수수료율 조회 실패 — overseasFee=0으로 처리: accountId={}", account.id());
                    return BigDecimal.ZERO;
                });

        List<DailyTransaction> items = executions.stream()
                .map(e -> new DailyTransaction(
                        e.tradeDate().toString(),
                        null,              // Toss — 결제일 미제공
                        e.direction(),
                        e.ticker(),
                        e.ticker().name(), // Toss — 한글 종목명 미제공
                        e.quantity(),
                        e.price(),
                        e.amountUsd(),
                        BigDecimal.ZERO,   // Toss — KRW 정산금액 미제공
                        BigDecimal.ZERO,   // Toss — 체결 시점 환율 미제공
                        "USD"
                ))
                .toList();

        BigDecimal buyTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.BUY)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.SELL)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        // overseasFee = 전체 거래금액 × 수수료율(%) / 100
        BigDecimal overseasFee = buyTotal.add(sellTotal)
                .multiply(usCommissionRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        return new DailyTransactionResult(items, new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, overseasFee));
    }

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
