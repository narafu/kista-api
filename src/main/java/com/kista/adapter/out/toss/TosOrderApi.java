package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.TosOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TosOrderApi implements TosOrderPort {

    // Toss 주문 API 경로
    private static final String ORDER_PATH = "/api/v1/orders";

    private final TossHttpClient tossHttpClient;

    @Override
    public Order place(Order order, Account account) {
        // Toss는 MARKET 주문 미지원 — MOC도 LIMIT+CLS로 대체
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", order.ticker().name());              // 종목 코드 (예: SOXL)
        body.put("side", order.direction().name());             // BUY / SELL
        body.put("orderType", "LIMIT");                         // Toss 지원 타입: LIMIT만
        body.put("timeInForce", resolveTimeInForce(order.orderType())); // CLS(장마감) or DAY(정규장)
        body.put("quantity", order.quantity());
        body.put("price", resolvePrice(order.orderType(), order.price()));

        // Toss API 응답: {"result": {"orderId": "...", "clientOrderId": "..."}} 래퍼 구조
        OrderResponseWrapper wrapper = tossHttpClient.post(
                ORDER_PATH, account, body, OrderResponseWrapper.class);

        // orderId 없으면 비즈니스 실패 처리
        if (wrapper == null || wrapper.result() == null || wrapper.result().orderId() == null) {
            throw new TossApiException("Toss 주문 실패: 응답에 orderId 없음", null);
        }

        // id=null — KIS와 동일하게 호출자가 DB 저장(plan/markPlaced) 처리
        return new Order(
                null, null, null, order.tradeDate(), order.ticker(),
                order.orderType(), order.direction(), order.quantity(), order.price(),
                Order.OrderStatus.PLACED, wrapper.result().orderId(), null, null
        );
    }

    @Override
    public void cancel(Order order, Account account) {
        // DELETE /api/v1/orders/{externalOrderId}
        tossHttpClient.delete(ORDER_PATH + "/" + order.externalOrderId(), account);
    }

    // LOC/MOC → 장마감 지정가(CLS), LIMIT → 정규장 지정가(DAY)
    private String resolveTimeInForce(Order.OrderType type) {
        return switch (type) {
            case LOC, MOC -> "CLS"; // MOC 미지원 → LIMIT+CLS로 장마감 경매 참여
            case LIMIT -> "DAY";
        };
    }

    // MOC 대체 주문: 최저가(0.01)로 장마감 경매에서 시장가처럼 체결되도록 유도
    private BigDecimal resolvePrice(Order.OrderType type, BigDecimal price) {
        return type == Order.OrderType.MOC ? new BigDecimal("0.01") : price;
    }

    // package-private — TosOrderApiTest에서 직접 생성하여 stub에 사용
    record OrderResponseWrapper(
        @JsonProperty("result") OrderResponse result  // Toss API 공통 {"result": {...}} 래퍼
    ) {}

    record OrderResponse(
        @JsonProperty("orderId") String orderId,
        @JsonProperty("clientOrderId") String clientOrderId
    ) {}
}
