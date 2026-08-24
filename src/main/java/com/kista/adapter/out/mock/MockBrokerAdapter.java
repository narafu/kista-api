package com.kista.adapter.out.mock;

import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.common.CycleLookups;
import com.kista.domain.backtest.FillSimulator;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.broker.*;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 모의계좌 어댑터 — 실제 증권사 접수 없이 DB 스냅샷 기반으로 잔고·체결을 시뮬레이션
@Component
@RequiredArgsConstructor
public class MockBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort {

    private final CommonMarketPriceFeed priceFeed;     // 시세 재사용 — Spring이 TossPriceApi 빈을 이 인터페이스로 주입
    private final OrderPort orderPort;                  // 사이클 기준 PLACED 주문 조회 (체결 시뮬레이션 대상)
    private final StrategyPort strategyPort;             // 계좌+ticker → strategy 해석
    private final StrategyCyclePort strategyCyclePort;   // strategy → 현재 활성 사이클 해석 (getExecutions의 cycle 격리용)
    private final CyclePositionPort cyclePositionPort;   // 최신 잔고 스냅샷 조회

    @Override
    public Account.Broker supports() {
        return Account.Broker.MOCK;
    }

    // --- 계좌+ticker → 전략 해석 공통 헬퍼 ---
    // Account에는 ticker 정보가 없다(전략이 소유) — 계좌에 속한 전략 중 ticker가 일치하는 것을 찾는다
    private Strategy resolveStrategy(Account account, Ticker ticker) {
        return strategyPort.findByAccountId(account.id()).stream()
                .filter(s -> s.ticker() == ticker)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌에 해당 종목 전략이 없습니다: accountId=" + account.id() + ", ticker=" + ticker));
    }

    // --- 계좌+ticker → 최신 포지션 해석 공통 헬퍼 ---
    private CyclePosition resolveLatestPosition(Account account, Ticker ticker) {
        Strategy strategy = resolveStrategy(account, ticker);
        return cyclePositionPort.findLatestOneByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌 포지션 이력이 없습니다: strategyId=" + strategy.id()));
    }

    // 계좌 전체 가용 예수금 — 실제 브로커는 계좌 단일 현금풀을 여러 전략이 공유하므로(TradingOrderBudgetAllocator가
    // 대표 전략 1개로 getLiveBalance를 호출해 계좌의 모든 BUY 후보에 그대로 적용) 모의계좌도 전략별 usdDeposit을
    // 합산해 계좌 단위 값으로 맞춘다 — 전략별 값을 그대로 반환하면 다른 전략의 잔고로 매수 승인/거절이 오염된다
    private BigDecimal sumUsdDepositAcrossStrategies(Account account) {
        return strategyPort.findByAccountId(account.id()).stream()
                .map(s -> cyclePositionPort.findLatestOneByStrategyId(s.id()))
                .flatMap(Optional::stream)
                .map(CyclePosition::usdDeposit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- BrokerPricePort (account 파라미터 무시, priceFeed에 위임 — Toss 패턴과 동일) ---

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        return priceFeed.getPrice(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return priceFeed.getPrices(tickers); // 공통 API — account 불필요
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return priceFeed.getPriceSnapshot(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return priceFeed.getPriceSnapshots(tickers); // 공통 API — account 불필요
    }

    @Override
    public BigDecimal getPrevClose(Ticker ticker, Account account) {
        return priceFeed.getPrevClose(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account) {
        return priceFeed.getPrevCloses(tickers); // 공통 API — account 불필요
    }

    // 모의계좌는 확정 종가 API가 없음 — Toss와 동일하게 라이브 현재가를 확정 종가로 대체 사용
    @Override
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return priceFeed.getPrice(ticker);
    }

    @Override
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        return priceFeed.getPrices(tickers);
    }

    // --- LiveBalancePort ---

    @Override
    public AccountBalance getLiveBalance(Account account, Ticker ticker) {
        // usdDeposit은 계좌 전체 합산(위 sumUsdDepositAcrossStrategies 주석 참고), holdings/avgPrice는 해당 ticker 전략 값
        CyclePosition position = resolveLatestPosition(account, ticker);
        return new AccountBalance(position.holdings(), position.avgPrice(), sumUsdDepositAcrossStrategies(account));
    }

    // --- SellableQuantityPort ---

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        int holdings = resolveLatestPosition(account, ticker).holdings();
        return new SellableQuantity(ticker.name(), holdings);
    }

    // --- BrokerOrderCorrectionPort ---

    @Override
    public Order place(Order order, Account account) {
        // 실제 증권사 접수 없이 합성 주문번호 부여 — 이 ID를 getExecutions()가 그대로 echo해 TradingReporter.markFilledOrders와 매칭시킨다
        return order.withPlaced("MOCK-" + UUID.randomUUID());
    }

    @Override
    public void cancel(Order order, Account account) {
        // no-op — 모의계좌는 별도 취소 대상이 없음(getExecutions에서 미체결 주문은 TradingReporter가 자체적으로 CANCELLED 처리)
    }

    // --- ExecutionPort — 체결 시뮬레이션 코어 ---
    // MOC: 항상 체결(종가) / LOC: 매수는 종가<=지정가, 매도는 종가>=지정가 (체결가는 종가)
    // LIMIT: 매수는 종가<=지정가, 매도는 종가>=지정가 (체결가는 지정가 그대로 — LOC와 달리 종가로 재계산하지 않음)
    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        // 실제 호출부(TradingReporter)는 항상 from==to(당일)로만 호출 — to를 거래일로 사용
        // strategyCycleId로 스코프 — account+ticker만으로 조회하면 사이클 롤오버 당일 종료된 이전 사이클의
        // 잔류 PLACED 주문(취소 실패 등)이 새 사이클의 체결에 잘못 합산될 수 있어 기존 사이클 격리 조회를 재사용한다
        Strategy strategy = resolveStrategy(account, ticker);
        StrategyCycle cycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        List<Order> placed = orderPort.findPlacedByCycleAndDate(cycle.id(), to);
        if (placed.isEmpty()) return List.of();

        BigDecimal closingPrice = getClosingPrice(ticker, to, account);
        List<Execution> executions = new ArrayList<>();
        for (Order order : placed) {
            if (!FillSimulator.fills(order, closingPrice)) continue; // 미체결 — TradingReporter.markFilledOrders가 CANCELLED로 기록
            // LIMIT은 지정가 그대로 체결, LOC/MOC는 종가 기준 체결
            BigDecimal fillPrice = order.orderType() == Order.OrderType.LIMIT ? order.price() : closingPrice;
            executions.add(Execution.ofManualFill(to, ticker, order.direction(), order.quantity(), fillPrice, order.externalOrderId()));
        }
        return executions;
    }

    // --- MarginPort ---

    // 신규 전략 등록 시점 게이트체크(StrategyService.calcFreeCash) 전용 — 등록하려는 전략은 아직 cycle_position이
    // 없어(chicken-and-egg) 실제 잔고를 계산할 수 없으므로 상한 없음으로 항상 통과시킨다.
    // 실제 매수 예산 제약은 배치 실행 시 LiveBalancePort.getLiveBalance()(계좌 합산)가 담당한다.
    // 주의: 이 값은 GET /api/accounts/{id}/margin에도 그대로 노출되므로 화면상 "가용현금"이 실제와 다르게 크게
    // 보일 수 있다 — 모의계좌 UI에서 이 필드는 참고용이 아님을 별도 안내하는 것을 권장한다.
    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return new BigDecimal("999999999.00");
    }

    @Override
    public List<MarginItem> getMargin(Account account) {
        BigDecimal buyable = getUsdBuyableAmount(account);
        return List.of(new MarginItem(Currency.USD, buyable, buyable, buyable, BigDecimal.ONE));
    }

    // --- PortfolioPort ---

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        List<Strategy> strategies = strategyPort.findByAccountId(account.id());
        List<PresentBalanceResult.TossHolding> holdings = new ArrayList<>();
        BigDecimal totalUsdDeposit = BigDecimal.ZERO;
        for (Strategy strategy : strategies) {
            Optional<CyclePosition> latest = cyclePositionPort.findLatestOneByStrategyId(strategy.id());
            if (latest.isEmpty()) continue;
            CyclePosition position = latest.get();
            totalUsdDeposit = totalUsdDeposit.add(position.usdDeposit());
            if (position.holdings() > 0 && position.avgPrice() != null) {
                BigDecimal currentPrice = priceFeed.getPrice(strategy.ticker());
                holdings.add(new PresentBalanceResult.TossHolding(strategy.ticker(), position.holdings(), position.avgPrice(), currentPrice));
            }
        }
        // 모의계좌는 KRW 환전 개념이 없음 — krwDeposit=0, rate=0으로 집계(문서화된 근사치, DB 스냅샷 기반)
        // rate=0이면 aggregateToss()가 hasRate=false 경로로 0으로 나누지 않고 안전하게 처리
        return PresentBalanceResult.aggregateToss(holdings, totalUsdDeposit, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
