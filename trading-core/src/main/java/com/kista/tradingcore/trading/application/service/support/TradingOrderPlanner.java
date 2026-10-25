package com.kista.tradingcore.trading.application.service.support;

import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// PLANNED 주문 저장 헬퍼 (전략 계산은 CycleOrderStrategy로 이전됨)
@Component
@RequiredArgsConstructor
@Slf4j
public class TradingOrderPlanner {

    private final OrderPort orderPort;

    // 이미 계산된 templates(커널 산출 PlannedOrder)를 특정 계좌·사이클의 PLANNED Order로 승격해 저장 — 유일한 승격 지점
    public void savePlannedOrders(List<PlannedOrder> templates, TradingAccount account, UUID strategyCycleId) {
        // leg 없는 주문은 중복 방지 슬롯(timing+direction+leg)에 잡히지 않아 같은 주문이 두 번 나갈 수 있다 — 배치·수동 실행·재캡 공용 차단
        long unknownLegCount = templates.stream().filter(o -> PlannedOrder.UNKNOWN_LEG.equals(o.orderLeg())).count();
        if (unknownLegCount > 0) {
            throw new IllegalStateException("주문 leg 누락 — 저장 거부: count=" + unknownLegCount);
        }
        List<Order> planned = templates.stream()
                .map(o -> Order.fromPlanned(o, account.id(), strategyCycleId))
                .toList();
        orderPort.saveAll(planned);
        log.info("[{}] 계획 주문 {}건 저장 (PLANNED)", account.nickname(), planned.size());
    }
}
