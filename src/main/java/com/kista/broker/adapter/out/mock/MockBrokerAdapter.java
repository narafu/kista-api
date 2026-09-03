package com.kista.broker.adapter.out.mock;

import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.broker.domain.model.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.application.port.output.StrategyPort;
import com.kista.broker.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 모의계좌 어댑터 — 실제 증권사 접수 없이 DB 스냅샷 기반으로 잔고·체결을 시뮬레이션
// trading 소유 영속 데이터는 MockSimulationDataPort(broker 소유 포트, trading이 구현) 경유로만 접근 — trading 타입 직접 참조 없음
@Component
@RequiredArgsConstructor
public class MockBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort {

    private final CommonMarketPriceFeed priceFeed;               // 시세 재사용 — Spring이 TossPriceApi 빈을 이 인터페이스로 주입 (이미 broker 소유 PriceSnapshot 반환)
    private final StrategyPort strategyPort;                     // 계좌+ticker → strategy 해석 (legacy 공개 포트)
    private final MockSimulationDataPort mockSimulationDataPort; // trading 소유 주문·사이클·포지션 조회 (포트 역전 — 클래스 주석 참고)

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
    private PositionView resolveLatestPosition(Account account, Ticker ticker) {
        Strategy strategy = resolveStrategy(account, ticker);
        return mockSimulationDataPort.findLatestPosition(strategy.id())
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌 포지션 이력이 없습니다: strategyId=" + strategy.id()));
    }

    // 계좌 전체 가용 예수금 — 실제 브로커는 계좌 단일 현금풀을 여러 전략이 공유하므로(TradingOrderBudgetAllocator가
    // 대표 전략 1개로 getLiveBalance를 호출해 계좌의 모든 BUY 후보에 그대로 적용) 모의계좌도 전략별 usdDeposit을
    // 합산해 계좌 단위 값으로 맞춘다 — 전략별 값을 그대로 반환하면 다른 전략의 잔고로 매수 승인/거절이 오염된다
    private BigDecimal sumUsdDepositAcrossStrategies(Account account) {
        return strategyPort.findByAccountId(account.id()).stream()
                .map(s -> mockSimulationDataPort.findLatestPosition(s.id()))
                .flatMap(Optional::stream)
                .map(PositionView::usdDeposit)
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
        return priceFeed.getPriceSnapshot(ticker); // 공통 API — account 불필요, priceFeed가 이미 broker 소유 PriceSnapshot 반환
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

    // tradeDate 일봉 확정 종가 — 시세는 Toss 공용 피드 재사용(CommonMarketPriceFeed.getClosingPrice)
    @Override
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return priceFeed.getClosingPrice(ticker, tradeDate);
    }

    @Override
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        for (Ticker ticker : tickers) {
            result.put(ticker, priceFeed.getClosingPrice(ticker, tradeDate));
        }
        return result;
    }

    // --- LiveBalancePort ---

    @Override
    public BrokerBalance getLiveBalance(Account account, Ticker ticker) {
        // usdDeposit은 계좌 전체 합산(위 sumUsdDepositAcrossStrategies 주석 참고), holdings/avgPrice는 해당 ticker 전략 값
        PositionView position = resolveLatestPosition(account, ticker);
        return new BrokerBalance(position.holdings(), position.avgPrice(), sumUsdDepositAcrossStrategies(account));
    }

    // --- SellableQuantityPort ---

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        int holdings = resolveLatestPosition(account, ticker).holdings();
        return new SellableQuantity(ticker.name(), holdings);
    }

    // --- BrokerOrderCorrectionPort ---

    @Override
    public OrderResult place(OrderInstruction instruction, Account account) {
        // 실제 증권사 접수 없이 합성 주문번호 부여 — 이 ID를 getExecutions()가 그대로 echo해 TradingReporter.markFilledOrders와 매칭시킨다
        return new OrderResult("MOCK-" + UUID.randomUUID());
    }

    @Override
    public void cancel(CancelInstruction instruction, Account account) {
        // no-op — 모의계좌는 별도 취소 대상이 없음(getExecutions에서 미체결 주문은 TradingReporter가 자체적으로 CANCELLED 처리)
    }

    // --- ExecutionPort — 체결 시뮬레이션 코어 ---
    // MOC: 항상 체결(종가) / LOC: 매수는 종가<=지정가, 매도는 종가>=지정가 (체결가는 종가)
    // LIMIT: 매수는 종가<=지정가, 매도는 종가>=지정가 (체결가는 지정가 그대로 — LOC와 달리 종가로 재계산하지 않음)
    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        // 실제 호출부(TradingReporter)는 항상 from==to(당일)로만 호출 — to를 거래일로 사용
        // cycleId로 스코프 — account+ticker만으로 조회하면 사이클 롤오버 당일 종료된 이전 사이클의
        // 잔류 PLACED 주문(취소 실패 등)이 새 사이클의 체결에 잘못 합산될 수 있어 활성 사이클 격리 조회를 재사용한다
        Strategy strategy = resolveStrategy(account, ticker);
        UUID cycleId = mockSimulationDataPort.findActiveCycleId(strategy.id());
        List<PlacedOrderView> placed = mockSimulationDataPort.findPlacedOrders(cycleId, to);
        if (placed.isEmpty()) return List.of();

        BigDecimal closingPrice = getClosingPrice(ticker, to, account);
        List<Execution> executions = new ArrayList<>();
        for (PlacedOrderView order : placed) {
            if (!fills(order, closingPrice)) continue; // 미체결 — TradingReporter.markFilledOrders가 CANCELLED로 기록
            // LIMIT은 지정가 그대로 체결, LOC/MOC는 종가 기준 체결
            BigDecimal fillPrice = order.orderType() == OrderType.LIMIT ? order.price() : closingPrice;
            executions.add(Execution.ofManualFill(to, ticker, order.direction(), order.quantity(), fillPrice, order.externalOrderId()));
        }
        return executions;
    }

    // 체결 판정 SSOT는 com.kista.stats.domain.backtest.FillSimulator.fills(Order, BigDecimal)이지만 trading의 Order를 받는다 —
    // broker는 더 이상 trading 타입을 참조할 수 없으므로 동일 판정 로직을 broker 소유 타입으로 재구현한다.
    // 순수 3줄 판정이라 포트 우회보다 저비용 복제로 판단(PersistenceSupport/DstInfo 부분 복제와 동일 기준, 변경 금지)
    private static boolean fills(PlacedOrderView order, BigDecimal closingPrice) {
        if (order.orderType() == OrderType.MOC) return true;
        return order.direction() == Direction.BUY
                ? closingPrice.compareTo(order.price()) <= 0
                : closingPrice.compareTo(order.price()) >= 0;
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
            Optional<PositionView> latest = mockSimulationDataPort.findLatestPosition(strategy.id());
            if (latest.isEmpty()) continue;
            PositionView position = latest.get();
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
