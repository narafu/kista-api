package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

// PLANNED 주문 저장 헬퍼 (전략 계산은 CycleOrderStrategy로 이전됨)
// package-private — application/service 패키지 전용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingOrderPlanner {

    private final OrderPort orderPort;

    // 이미 계산된 templates를 orders에 PLANNED 상태로 저장
    void savePlannedOrders(List<Order> templates, Account account) {
        List<Order> planned = templates.stream()
                .map(o -> Order.plan(o, account.id()))
                .toList();
        orderPort.saveAll(planned);
        log.info("[{}] 계획 주문 {}건 저장 (PLANNED)", account.nickname(), planned.size());
    }
}
