package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Order;
import com.kista.domain.port.out.KisOrderPort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class KisOrderAdapter implements KisOrderPort {

    private static final String PATH      = "/uapi/overseas-stock/v1/trading/order";
    private static final String BUY_TR_ID  = "TTTS0308U"; // 해외주식 매수 주문
    private static final String SELL_TR_ID = "TTTS0307U"; // 해외주식 매도 주문

    private final KisHttpClient kisHttpClient;

    public KisOrderAdapter(KisHttpClient kisHttpClient) {
        this.kisHttpClient = kisHttpClient;
    }

    @Override
    public Order place(String token, Order order) {
        String trId = order.direction() == Order.OrderDirection.BUY ? BUY_TR_ID : SELL_TR_ID;
        HttpHeaders headers = kisHttpClient.buildHeaders(token, trId);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", kisHttpClient.props().accountNo());
        body.put("ACNT_PRDT_CD", kisHttpClient.props().accountType());
        body.put("OVRS_EXCG_CD", kisHttpClient.props().exchangeCode());
        body.put("PDNO", order.symbol());
        body.put("ORD_DVSN", resolveOrderDvsn(order.orderType()));
        body.put("ORD_QTY", String.valueOf(order.qty()));
        body.put("OVRS_ORD_UNPR", resolvePrice(order.orderType(), order.price()));
        body.put("ORD_SVR_DVSN_CD", "0");

        OrderResponse response = kisHttpClient.post(PATH, headers, body, OrderResponse.class);
        String odno = (response != null && response.output() != null) ? response.output().odno() : null;

        return new Order(
                order.tradeDate(), order.symbol(), order.orderType(), order.direction(),
                order.qty(), order.price(), Order.OrderStatus.PLACED, odno
        );
    }

    private String resolveOrderDvsn(Order.OrderType type) {
        return switch (type) {
            case LOC   -> "32"; // 장마감지정가(LOC)
            case MOC   -> "34"; // 장마감시장가(MOC)
            case LIMIT -> "00"; // 지정가
        };
    }

    private String resolvePrice(Order.OrderType type, BigDecimal price) {
        return switch (type) {
            case LOC, MOC -> "0";           // LOC/MOC는 가격 무관하므로 0 입력
            case LIMIT    -> price.toPlainString();
        };
    }

    record OrderResponse(@JsonProperty("output") Output output) {
        record Output(@JsonProperty("ODNO") String odno) {}
    }
}
