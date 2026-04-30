package com.kista.application.service;

import com.kista.domain.model.*;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class TradingService implements ExecuteTradingUseCase {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final String symbol;                              // 거래 종목 코드 (기본: SOXL)
    private final KisTokenPort kisTokenPort;                  // KIS OAuth 토큰 발급
    private final KisHolidayPort kisHolidayPort;              // 미국 시장 개장일 확인
    private final KisAccountPort kisAccountPort;              // 계좌 잔고 조회
    private final KisPricePort kisPricePort;                  // 현재 주가 조회
    private final KisOrderPort kisOrderPort;                  // 주문 접수
    private final KisExecutionPort kisExecutionPort;          // 당일 체결 내역 조회
    private final TradingStrategy tradingStrategy;            // 매수/매도 수량·가격 계산
    private final CorrectionStrategy correctionStrategy;      // 미체결 주문 보정
    private final TradeHistoryPort tradeHistoryPort;          // 거래 이력 저장
    private final PortfolioSnapshotPort portfolioSnapshotPort; // 포트폴리오 스냅샷 저장
    private final NotifyPort notifyPort;                      // 텔레그램 알림 발송

    public TradingService(
            @Value("${kis.symbol:SOXL}") String symbol,
            KisTokenPort kisTokenPort,
            KisHolidayPort kisHolidayPort,
            KisAccountPort kisAccountPort,
            KisPricePort kisPricePort,
            KisOrderPort kisOrderPort,
            KisExecutionPort kisExecutionPort,
            TradingStrategy tradingStrategy,
            CorrectionStrategy correctionStrategy,
            TradeHistoryPort tradeHistoryPort,
            PortfolioSnapshotPort portfolioSnapshotPort,
            NotifyPort notifyPort) {
        this.symbol = symbol;
        this.kisTokenPort = kisTokenPort;
        this.kisHolidayPort = kisHolidayPort;
        this.kisAccountPort = kisAccountPort;
        this.kisPricePort = kisPricePort;
        this.kisOrderPort = kisOrderPort;
        this.kisExecutionPort = kisExecutionPort;
        this.tradingStrategy = tradingStrategy;
        this.correctionStrategy = correctionStrategy;
        this.tradeHistoryPort = tradeHistoryPort;
        this.portfolioSnapshotPort = portfolioSnapshotPort;
        this.notifyPort = notifyPort;
    }

    @Override
    public void execute() throws InterruptedException {
        execute(DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void execute(DstInfo dst) throws InterruptedException {
        // LOC 마감 시각까지 대기 (장 마감 직전 LOC 주문 가능 시점 확보)
        long locWaitMs = dst.waitUntilLocDeadline().toMillis();
        log.info("DST={}, LOC 마감까지 대기: {}ms", dst.isDst(), locWaitMs);
        if (locWaitMs > 0) Thread.sleep(locWaitMs);
        log.info("LOC 마감 대기 완료");

        LocalDate today = LocalDate.now();
        // KIS API 호출용 OAuth 토큰 발급
        String token = kisTokenPort.getToken();
        log.info("KIS 토큰 발급 완료");

        // 미국 시장 휴장일 확인
        boolean marketOpen = kisHolidayPort.isMarketOpen(token, today);
        log.info("시장 개장 여부: {}", marketOpen);
        if (!marketOpen) {
            log.info("휴장일 — 매매 건너뜀");
            notifyPort.notifyMarketClosed();
            return;
        }

        // 계좌 잔고 조회 및 최소 거래 금액 미달 시 종료
        AccountBalance balance = kisAccountPort.getBalance(token);
        log.info("잔고 조회: SOXL {}주, 예수금 ${}, 유효잔고 ${}", balance.quantity(), balance.usdDeposit(), balance.effectiveAmt());
        if (balance.shouldSkip()) {
            log.info("잔고 부족 — 매매 건너뜀");
            notifyPort.notifyInsufficientBalance(balance);
            return;
        }

        // 전략 계산 후 BUY/SELL LOC 주문 접수
        BigDecimal price = kisPricePort.getPrice(token, symbol);
        log.info("현재가: ${}", price);
        TradingVariables vars = tradingStrategy.calculate(balance, price);
        log.info("전략 계산: priceOffsetRate={}, currentRound={}, unitAmount={}", vars.priceOffsetRate(), vars.currentRound(), vars.unitAmount());
        List<Order> mainOrders = placeMainOrders(token, vars, today);
        log.info("LOC 주문 {}건 접수", mainOrders.size());

        // PostClose 시각까지 대기 (체결 내역이 KIS에 반영될 때까지)
        long postWaitMs = dst.waitUntilPostClose().toMillis();
        log.info("PostClose까지 대기: {}ms", postWaitMs);
        if (postWaitMs > 0) Thread.sleep(postWaitMs);
        log.info("PostClose 대기 완료");

        // 당일 체결 내역 조회 후 미체결 주문 LIMIT으로 보정
        List<Execution> executions = kisExecutionPort.getExecutions(token, today);
        log.info("체결 내역 {}건 조회", executions.size());
        List<Order> corrections = correctionStrategy.correct(mainOrders, executions, today)
                .stream()
                .map(o -> kisOrderPort.place(token, o))
                .toList();
        log.info("보정 주문 {}건", corrections.size());

        // 거래 이력 저장
        mainOrders.forEach(o -> tradeHistoryPort.save(toHistory(o)));
        corrections.forEach(o -> tradeHistoryPort.save(toHistory(o)));
        log.info("거래 이력 {}건 저장", mainOrders.size() + corrections.size());

        // 포트폴리오 스냅샷 저장 후 텔레그램 리포트 발송
        portfolioSnapshotPort.save(toSnapshot(balance, price, today));
        log.info("포트폴리오 스냅샷 저장 완료");
        notifyPort.notifyReport(buildReport(today, vars, mainOrders, corrections, executions));
        log.info("텔레그램 리포트 발송 완료");
    }

    private List<Order> placeMainOrders(String token, TradingVariables vars, LocalDate today) {
        List<Order> orders = new ArrayList<>();

        // BUY LOC: 총 포트폴리오의 priceOffsetRate 비중 매수
        int buyQty = vars.totalAssets().multiply(vars.priceOffsetRate())
                .divide(vars.currentPrice(), 0, RoundingMode.FLOOR)
                .intValue();
        if (buyQty >= 1) {
            orders.add(kisOrderPort.place(token, new Order(
                    today, symbol, Order.OrderType.LOC, Order.OrderDirection.BUY,
                    buyQty, vars.currentPrice(), Order.OrderStatus.PLACED, null)));
        }

        // SELL LOC: currentRound > 0이고 보유 수량 있는 경우 1회차분 매도
        if (vars.quantity() > 0 && vars.currentRound() > 0) {
            int sellQty = vars.unitAmount().divide(vars.targetPrice(), 0, RoundingMode.FLOOR).intValue();
            if (sellQty >= 1) {
                orders.add(kisOrderPort.place(token, new Order(
                        today, symbol, Order.OrderType.LOC, Order.OrderDirection.SELL,
                        sellQty, vars.targetPrice(), Order.OrderStatus.PLACED, null)));
            }
        }

        return orders;
    }

    private TradeHistory toHistory(Order o) {
        BigDecimal amountUsd = o.price().multiply(BigDecimal.valueOf(o.qty()))
                .setScale(2, RoundingMode.HALF_UP);
        return new TradeHistory(
                null, o.tradeDate(), o.symbol(), "SOXL_DIVISION",
                o.orderType(), o.direction(), o.qty(), o.price(),
                amountUsd, o.status(), o.kisOrderId(), null);
    }

    private PortfolioSnapshot toSnapshot(AccountBalance balance, BigDecimal price, LocalDate today) {
        BigDecimal marketValue = price.multiply(BigDecimal.valueOf(balance.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAsset = marketValue.add(balance.usdDeposit())
                .setScale(2, RoundingMode.HALF_UP);
        return new PortfolioSnapshot(
                null, today, symbol, balance.quantity(), balance.avgPrice(),
                price, marketValue, balance.usdDeposit(), totalAsset, null);
    }

    private TradingReport buildReport(LocalDate today, TradingVariables vars,
                                      List<Order> mainOrders, List<Order> corrections,
                                      List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, vars, mainOrders, corrections, totalBought, totalSold);
    }
}
