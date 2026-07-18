package com.kista.adapter.out.persistence.privacy;

import com.kista.common.TimeZones;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
class PrivacyTradePersistenceAdapter implements PrivacyTradePort {

    // BUY → SELL, BUY는 price 내림차순, SELL은 price 오름차순 (제네릭 헬퍼 — 도메인/엔티티 공용)
    private static <T> Comparator<T> orderSort(Function<T, Order.OrderDirection> dirFn,
                                               Function<T, BigDecimal> priceFn) {
        return Comparator.<T, Integer>comparing(t -> dirFn.apply(t) == Order.OrderDirection.BUY ? 0 : 1)
                .thenComparing((a, b) -> dirFn.apply(a) == Order.OrderDirection.BUY
                        ? priceFn.apply(b).compareTo(priceFn.apply(a))
                        : priceFn.apply(a).compareTo(priceFn.apply(b)));
    }

    private static final Comparator<Order> ORDER_SORT =
            orderSort(Order::direction, Order::price);

    // 주문 표시 정렬 (엔티티용 — 동일 기준)
    private static final Comparator<PrivacyTradeBaseOrderEntity> BASE_ORDER_SORT =
            orderSort(PrivacyTradeBaseOrderEntity::getDirection, PrivacyTradeBaseOrderEntity::getPrice);

    private final PrivacyTradeBaseJpaRepository baseRepository;

    @Override
    @Transactional
    public PrivacyTradeSaveResult saveBaseWithOrders(FidaOrderCommand command) {
        Optional<PrivacyTradeBaseEntity> existing = this.getByReleaseDateAndTicker(command.tradeDate(), command.ticker());

        if (existing.isPresent()) {
            // 동일 (releaseDate, ticker) 존재 — 내용 비교
            PrivacyTradeBaseEntity base = existing.get();
            if (isIdentical(base, command)) {
                return new PrivacyTradeSaveResult(base.getId(), false); // 200
            }
            throw new PrivacyTradeConflictException(
                    "기존 매매표와 내용이 다릅니다: tradeDate=" + command.tradeDate() + ", ticker=" + command.ticker());
        }

        // 신규 저장
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setReleaseDate(command.tradeDate()); // FIDA 발행일 원본 그대로 (Task 3에서 command.releaseDate()로 리네임)
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

    private Optional<PrivacyTradeBaseEntity> getByReleaseDateAndTicker(LocalDate releaseDate, Ticker ticker) {
        return baseRepository.findByReleaseDateAndTicker(releaseDate, ticker); // 발행일 정확 일치
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
            if (!Objects.equals(e.getQuantity(), o.quantity())) return false;
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
    public Optional<PrivacyCurrentBase> findSeedPreviewBase() {
        // 미리보기는 KST 오늘 거래일 이후에 적용되는 기준표만 사용 — 발행일은 거래일 전날
        LocalDate todayKst = LocalDate.now(TimeZones.KST);
        return baseRepository
                .findFirstByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(
                        PrivacyDates.releaseDateFor(todayKst), Ticker.SOXL)
                .map(e -> new PrivacyCurrentBase(e.getTicker(), e.getCurrentCycleStart(),
                        PrivacyDates.tradeDateOf(e.getReleaseDate()))); // 발행일 → 적용 거래일
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrivacyTradeBase> findTodayTrade(LocalDate today) {
        // today는 KST 거래일, >= 로 조회 — 오늘(토/공휴일)에 다음 거래일(월) 매매표도 인식
        return baseRepository.findFirstWithOrdersByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(
                        PrivacyDates.releaseDateFor(today), Ticker.SOXL)
                .map(entity -> {
                    LocalDate kstTradeDate = PrivacyDates.tradeDateOf(entity.getReleaseDate()); // 발행일 → 적용 거래일
                    List<PrivacyTradeBase.PrivacyTrade> trades = entity.getOrders().stream()
                            .sorted(BASE_ORDER_SORT)
                            .map(p -> new PrivacyTradeBase.PrivacyTrade(
                                    kstTradeDate, entity.getTicker(), p.getOrderType(), p.getDirection(), p.getQuantity(), p.getPrice()))
                            .toList();
                    return new PrivacyTradeBase(entity.getId(), entity.getAvgPrice(), entity.getHoldings(),
                            entity.getCurrentCycleStart(), trades);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public PrivacyTradeBase findBaseIfPrivacy(Strategy strategy, LocalDate today) {
        return strategy.isPrivacy() ? findTodayTrade(today).orElse(null) : null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate) {
        return baseRepository.findBasesFromReleaseDate(fromReleaseDate).stream()
                .map(this::toView)
                .toList();
    }

    // 엔티티 → 조회 뷰 변환 (관리자 표시용 — 발행일 원본 그대로, 이제 예외가 아니라 규칙)
    private PrivacyTradeBaseView toView(PrivacyTradeBaseEntity e) {
        List<PrivacyTradeBaseView.OrderLine> orders = e.getOrders().stream()
                .sorted(BASE_ORDER_SORT)
                .map(d -> new PrivacyTradeBaseView.OrderLine(
                        d.getId(), d.getDirection().name(), d.getOrderType().name(), d.getPrice(), d.getQuantity()))
                .toList();
        return new PrivacyTradeBaseView(
                e.getId(),
                e.getReleaseDate(),
                e.getTicker().name(),
                e.getCurrentCycleStart(),
                e.getCurrentCycleRealizedPnl(),
                e.getAvgPrice(),
                e.getHoldings(),
                orders);
    }
}
