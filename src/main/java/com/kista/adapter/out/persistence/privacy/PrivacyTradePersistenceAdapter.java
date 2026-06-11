package com.kista.adapter.out.persistence.privacy;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyCurrentBase;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import com.kista.domain.model.privacy.PrivacyTradeConflictException;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
class PrivacyTradePersistenceAdapter implements PrivacyTradePort {

    // BUY → SELL, BUY는 price 내림차순, SELL은 price 오름차순
    private static final Comparator<Order> ORDER_SORT = Comparator
            .comparingInt((Order o) -> o.direction() == Order.OrderDirection.BUY ? 0 : 1)
            .thenComparing((a, b) -> a.direction() == Order.OrderDirection.BUY
                    ? b.price().compareTo(a.price())
                    : a.price().compareTo(b.price()));

    // 주문 표시 정렬: BUY → SELL, BUY는 price 내림차순, SELL은 price 오름차순
    private static final Comparator<PrivacyTradeDetailEntity> DETAIL_SORT = Comparator
            .comparingInt((PrivacyTradeDetailEntity d) -> d.getDirection() == Order.OrderDirection.BUY ? 0 : 1)
            .thenComparing((a, b) -> a.getDirection() == Order.OrderDirection.BUY
                    ? b.getPrice().compareTo(a.getPrice())
                    : a.getPrice().compareTo(b.getPrice()));

    private final PrivacyTradeMasterJpaRepository masterRepository;

    @Override
    @Transactional
    public PrivacyTradeSaveResult saveMasterWithDetails(FidaOrderCommand command) {
        Optional<PrivacyTradeMasterEntity> existing = this.getByTradeDateAndTicker(command.tradeDate(), command.ticker());

        if (existing.isPresent()) {
            // 동일 (tradeDate, ticker) 존재 — 내용 비교
            PrivacyTradeMasterEntity master = existing.get();
            if (isIdentical(master, command)) {
                return new PrivacyTradeSaveResult(master.getId(), false); // 200
            }
            throw new PrivacyTradeConflictException(
                    "기존 매매표와 내용이 다릅니다: tradeDate=" + command.tradeDate() + ", ticker=" + command.ticker());
        }

        // 신규 저장
        PrivacyTradeMasterEntity master = new PrivacyTradeMasterEntity();
        master.setTradeDate(TradeDateConverter.toUtc(command.tradeDate())); // KST 도메인 → UTC DB
        master.setTicker(command.ticker());
        master.setCurrentCycleStart(command.currentCycleStart());
        master.setCurrentCycleRealizedPnl(command.currentCycleRealizedPnl());
        master.setAvgPrice(command.avgPrice());
        master.setHoldings(command.holdings());

        // BUY → SELL 순 정렬 후 detail 생성 — cascade로 함께 저장
        List<Order> sorted = command.orders().stream().sorted(ORDER_SORT).toList();
        for (Order order : sorted) {
            PrivacyTradeDetailEntity detail = new PrivacyTradeDetailEntity();
            detail.setPrivacyTrade(master);
            detail.setDirection(order.direction());
            detail.setOrderType(order.orderType());
            detail.setQuantity(order.quantity());
            detail.setPrice(order.price());
            master.getOrders().add(detail);
        }

        return new PrivacyTradeSaveResult(masterRepository.save(master).getId(), true); // 201
    }

    private Optional<PrivacyTradeMasterEntity> getByTradeDateAndTicker(LocalDate kstDate, Ticker ticker) {
        return masterRepository.findByTradeDateAndTicker(TradeDateConverter.toUtc(kstDate), ticker); // KST → UTC DB
    }

    private boolean isIdentical(PrivacyTradeMasterEntity master, FidaOrderCommand command) {
        if (master.getCurrentCycleStart().compareTo(command.currentCycleStart()) != 0) return false;
        if (master.getCurrentCycleRealizedPnl().compareTo(command.currentCycleRealizedPnl()) != 0) return false;
        if (!bigDecimalEquals(master.getAvgPrice(), command.avgPrice())) return false;
        if (master.getHoldings() != command.holdings()) return false;

        // detail 비교 — 동일한 정렬 기준으로 맞춘 후 순서대로 비교
        List<PrivacyTradeDetailEntity> existingDetails = master.getOrders().stream()
                .sorted(Comparator
                        .comparingInt((PrivacyTradeDetailEntity d) -> d.getDirection() == Order.OrderDirection.BUY ? 0 : 1)
                        .thenComparing((a, b) -> a.getDirection() == Order.OrderDirection.BUY
                                ? b.getPrice().compareTo(a.getPrice())
                                : a.getPrice().compareTo(b.getPrice())))
                .toList();
        List<Order> incomingOrders = command.orders().stream().sorted(ORDER_SORT).toList();

        if (existingDetails.size() != incomingOrders.size()) return false;
        for (int i = 0; i < existingDetails.size(); i++) {
            PrivacyTradeDetailEntity d = existingDetails.get(i);
            Order o = incomingOrders.get(i);
            if (d.getDirection() != o.direction()) return false;
            if (d.getOrderType() != o.orderType()) return false;
            if (!quantityEquals(d.getQuantity(), o.quantity())) return false;
            if (d.getPrice().compareTo(o.price()) != 0) return false;
        }
        return true;
    }

    private static boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    @Override
    public Optional<PrivacyCurrentBase> findCurrentBase() {
        // trade_date(UTC) >= 오늘(UTC)인 행 중 가장 미래 SOXL 기준가 조회
        LocalDate todayUtc = TradeDateConverter.toUtc(LocalDate.now()); // KST now → UTC
        return masterRepository
                .findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateDesc(todayUtc, Ticker.SOXL)
                .map(e -> new PrivacyCurrentBase(e.getTicker(), e.getCurrentCycleStart(),
                        TradeDateConverter.toKst(e.getTradeDate()))); // UTC DB → KST 도메인
    }

    @Override
    public Optional<PrivacyTradeBase> findTodayTrade(LocalDate today) {
        // today는 KST 일자 — getByTradeDateAndTicker 내부에서 UTC 변환
        return this.getByTradeDateAndTicker(today, Ticker.SOXL)
                .map(entity -> {
                    LocalDate kstTradeDate = TradeDateConverter.toKst(entity.getTradeDate()); // UTC DB → KST 도메인
                    List<PrivacyTradeBase.PrivacyTrade> trades = entity.getOrders().stream()
                            .map(p -> new PrivacyTradeBase.PrivacyTrade(
                                    kstTradeDate, entity.getTicker(), p.getOrderType(), p.getDirection(), p.getQuantity(), p.getPrice()))
                            .toList();
                    return new PrivacyTradeBase(entity.getId(), entity.getAvgPrice(), entity.getHoldings(),
                            entity.getCurrentCycleStart(), trades);
                });
    }

    private static boolean quantityEquals(Integer a, Integer b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromUtc) {
        return masterRepository.findBasesFromTradeDate(fromUtc).stream()
                .map(this::toView)
                .toList();
    }

    // 엔티티 → 조회 뷰 변환 (trade_date UTC → KST, 주문 정렬)
    private PrivacyTradeBaseView toView(PrivacyTradeMasterEntity e) {
        List<PrivacyTradeBaseView.OrderLine> orders = e.getOrders().stream()
                .sorted(DETAIL_SORT)
                .map(d -> new PrivacyTradeBaseView.OrderLine(
                        d.getId(), d.getDirection().name(), d.getOrderType().name(), d.getPrice(), d.getQuantity()))
                .toList();
        return new PrivacyTradeBaseView(
                e.getId(),
                TradeDateConverter.toKst(e.getTradeDate()),  // UTC DB → KST 도메인
                e.getTicker().name(),
                e.getCurrentCycleStart(),
                e.getCurrentCycleRealizedPnl(),
                e.getAvgPrice(),
                e.getHoldings(),
                orders);
    }
}
