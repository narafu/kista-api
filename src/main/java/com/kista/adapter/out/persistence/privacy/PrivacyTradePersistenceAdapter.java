package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeConflictException;
import com.kista.domain.model.privacy.FidaOrderRequest;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final PrivacyTradeMasterJpaRepository masterRepository;

    @Override
    @Transactional
    public PrivacyTradeSaveResult saveMasterWithDetails(FidaOrderRequest request) {
        Optional<PrivacyTradeMasterEntity> existing =
                masterRepository.findByTradeDateAndTicker(request.tradeDate(), request.ticker());

        if (existing.isPresent()) {
            // 동일 (tradeDate, ticker) 존재 — 내용 비교
            PrivacyTradeMasterEntity master = existing.get();
            if (isIdentical(master, request)) {
                return new PrivacyTradeSaveResult(master.getId(), false); // 200
            }
            throw new PrivacyTradeConflictException(
                    "기존 매매표와 내용이 다릅니다: tradeDate=" + request.tradeDate() + ", ticker=" + request.ticker());
        }

        // 신규 저장
        PrivacyTradeMasterEntity master = new PrivacyTradeMasterEntity();
        master.setTradeDate(request.tradeDate());
        master.setTicker(request.ticker());
        master.setCurrentCycleStart(request.currentCycleStart());
        master.setCurrentCycleRealizedPnl(request.currentCycleRealizedPnl());
        master.setAvgPrice(request.avgPrice());
        master.setHoldings(request.holdings());

        // BUY → SELL 순 정렬 후 detail 생성 — cascade로 함께 저장
        List<Order> sorted = request.orders().stream().sorted(ORDER_SORT).toList();
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

    private boolean isIdentical(PrivacyTradeMasterEntity master, FidaOrderRequest request) {
        if (master.getCurrentCycleStart().compareTo(request.currentCycleStart()) != 0) return false;
        if (master.getCurrentCycleRealizedPnl().compareTo(request.currentCycleRealizedPnl()) != 0) return false;
        if (!bigDecimalEquals(master.getAvgPrice(), request.avgPrice())) return false;
        if (master.getHoldings() != request.holdings()) return false;

        // detail 비교 — 동일한 정렬 기준으로 맞춘 후 순서대로 비교
        List<PrivacyTradeDetailEntity> existingDetails = master.getOrders().stream()
                .sorted(Comparator
                        .comparingInt((PrivacyTradeDetailEntity d) -> d.getDirection() == Order.OrderDirection.BUY ? 0 : 1)
                        .thenComparing((a, b) -> a.getDirection() == Order.OrderDirection.BUY
                                ? b.getPrice().compareTo(a.getPrice())
                                : a.getPrice().compareTo(b.getPrice())))
                .toList();
        List<Order> incomingOrders = request.orders().stream().sorted(ORDER_SORT).toList();

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

    private static boolean quantityEquals(Integer a, Integer b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
