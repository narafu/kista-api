package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PrivacyTradePersistenceAdapter implements PrivacyTradePort {

    private final PrivacyTradeMasterJpaRepository masterRepository;

    @Override
    @Transactional
    public void saveMasterWithDetails(FidaOrderRequest request) {
        // master 엔티티 생성 및 필드 세팅
        PrivacyTradeMasterEntity master = new PrivacyTradeMasterEntity();
        master.setTradeDate(request.tradeDate());
        master.setTicker(request.ticker());
        master.setCurrentCycleStart(request.currentCycleStart());
        master.setCurrentCycleRealizedPnl(request.currentCycleRealizedPnl());
        master.setAvgPrice(request.avgPrice());
        master.setHoldings(request.holdings());

        // 주문 명세(detail) 생성 — master에 연결 후 cascade로 함께 저장
        for (Order order : request.orders()) {
            PrivacyTradeDetailEntity detail = new PrivacyTradeDetailEntity();
            detail.setPrivacyTrade(master);
            detail.setDirection(order.direction());
            detail.setOrderType(order.orderType());
            detail.setQuantity(order.quantity());
            detail.setPrice(order.price());
            master.getOrders().add(detail);
        }

        masterRepository.save(master);
    }
}
