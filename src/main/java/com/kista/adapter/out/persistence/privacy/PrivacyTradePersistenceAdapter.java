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
    private static final Comparator<PrivacyTradeBaseOrderEntity> BASE_ORDER_SORT = Comparator
            .comparingInt((PrivacyTradeBaseOrderEntity o) -> o.getDirection() == Order.OrderDirection.BUY ? 0 : 1)
            .thenComparing((a, b) -> a.getDirection() == Order.OrderDirection.BUY
                    ? b.getPrice().compareTo(a.getPrice())
                    : a.getPrice().compareTo(b.getPrice()));

    private final PrivacyTradeBaseJpaRepository baseRepository;

    @Override
    @Transactional
    public PrivacyTradeSaveResult saveBaseWithOrders(FidaOrderCommand command) {
        Optional<PrivacyTradeBaseEntity> existing = this.getByTradeDateAndTicker(command.tradeDate(), command.ticker());

        if (existing.isPresent()) {
            // 동일 (tradeDate, ticker) 존재 — 내용 비교
            PrivacyTradeBaseEntity base = existing.get();
            if (isIdentical(base, command)) {
                return new PrivacyTradeSaveResult(base.getId(), false); // 200
            }
            throw new PrivacyTradeConflictException(
                    "기존 매매표와 내용이 다릅니다: tradeDate=" + command.tradeDate() + ", ticker=" + command.ticker());
        }

        // 신규 저장
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setTradeDate(TradeDateConverter.toUtc(command.tradeDate())); // KST 도메인 → UTC DB
        base.setTicker(command.ticker());
        base.setCurrentCycleStart(command.currentCycleStart());
        base.setCurrentCycleRealizedPnl(command.currentCycleRealizedPnl());
        base.setAvgPrice(command.avgPrice());
        base.setHoldings(command.holdings());

        // BUY → SELL 순 정렬 후 주문 생성 — cascade로 함께 저장
        List<Order> sorted = command.orders().stream().sorted(ORDER_SORT).toList();
        for (Order order : sorted) {
            PrivacyTradeBaseOrderEntity baseOrder = new PrivacyTradeBaseOrderEntity();
            baseOrder.setPrivacyBase(base);
            baseOrder.setDirection(order.direction());
            baseOrder.setOrderType(order.orderType());
            baseOrder.setQuantity(order.quantity());
            baseOrder.setPrice(order.price());
            base.getOrders().add(baseOrder);
        }

        return new PrivacyTradeSaveResult(baseRepository.save(base).getId(), true); // 201
    }

    private Optional<PrivacyTradeBaseEntity> getByTradeDateAndTicker(LocalDate kstDate, Ticker ticker) {
        return baseRepository.findByTradeDateAndTicker(TradeDateConverter.toUtc(kstDate), ticker); // KST → UTC DB, 정확한 날짜 일치
    }

    private boolean isIdentical(PrivacyTradeBaseEntity base, FidaOrderCommand command) {
        if (base.getCurrentCycleStart().compareTo(command.currentCycleStart()) != 0) return false;
        if (base.getCurrentCycleRealizedPnl().compareTo(command.currentCycleRealizedPnl()) != 0) return false;
        if (!bigDecimalEquals(base.getAvgPrice(), command.avgPrice())) return false;
        if (base.getHoldings() != command.holdings()) return false;

        // 주문 비교 — 동일한 정렬 기준으로 맞춘 후 순서대로 비교
        List<PrivacyTradeBaseOrderEntity> existingOrders = base.getOrders().stream()
                .sorted(BASE_ORDER_SORT)
                .toList();
        List<Order> incomingOrders = command.orders().stream().sorted(ORDER_SORT).toList();

        if (existingOrders.size() != incomingOrders.size()) return false;
        for (int i = 0; i < existingOrders.size(); i++) {
            PrivacyTradeBaseOrderEntity e = existingOrders.get(i);
            Order o = incomingOrders.get(i);
            if (e.getDirection() != o.direction()) return false;
            if (e.getOrderType() != o.orderType()) return false;
            if (!quantityEquals(e.getQuantity(), o.quantity())) return false;
            if (e.getPrice().compareTo(o.price()) != 0) return false;
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
        return baseRepository
                .findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateDesc(todayUtc, Ticker.SOXL)
                .map(e -> new PrivacyCurrentBase(e.getTicker(), e.getCurrentCycleStart(),
                        TradeDateConverter.toKst(e.getTradeDate()))); // UTC DB → KST 도메인
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrivacyTradeBase> findTodayTrade(LocalDate today) {
        // today는 KST 일자, >= 로 조회 — 오늘(토/공휴일)에 다음 거래일(월) 매매표도 인식
        return baseRepository.findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(
                        TradeDateConverter.toUtc(today), Ticker.SOXL)
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
        return baseRepository.findBasesFromTradeDate(fromUtc).stream()
                .map(this::toView)
                .toList();
    }

    // 엔티티 → 조회 뷰 변환 (trade_date UTC → KST, 주문 정렬)
    private PrivacyTradeBaseView toView(PrivacyTradeBaseEntity e) {
        List<PrivacyTradeBaseView.OrderLine> orders = e.getOrders().stream()
                .sorted(BASE_ORDER_SORT)
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
