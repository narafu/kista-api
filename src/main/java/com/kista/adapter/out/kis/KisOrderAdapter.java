package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.out.KisOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KisOrderAdapter implements KisOrderPort {

    private static final String PATH        = "/uapi/overseas-stock/v1/trading/order";
    private static final String CANCEL_PATH = "/uapi/overseas-stock/v1/trading/order-rvsecncl";
    private static final String BUY_TR_ID   = "TTTT1002U"; // 미국 해외주식 매수 주문
    private static final String SELL_TR_ID  = "TTTT1006U"; // 미국 해외주식 매도 주문
    private static final String CANCEL_TR_ID = "TTTT1004U"; // 미국 해외주식 정정취소주문

    private final KisHttpClient kisHttpClient;

    @Override
    public Order place(Order order, Account account) {
        String trId = order.direction() == Order.OrderDirection.BUY ? BUY_TR_ID : SELL_TR_ID;
        HttpHeaders headers = kisHttpClient.buildHeaders(trId, account);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", account.accountNo());
        body.put("ACNT_PRDT_CD", account.kisAccountType());
        body.put("OVRS_EXCG_CD", order.ticker().getExchangeCode().name());
        body.put("PDNO", order.ticker().name());
        body.put("ORD_DVSN", resolveOrderDvsn(order.orderType()));
        body.put("ORD_QTY", String.valueOf(order.quantity()));
        body.put("OVRS_ORD_UNPR", resolvePrice(order.orderType(), order.price()));
        body.put("CTAC_TLNO", "");       // 연락전화번호 (빈값 허용)
        body.put("MGCO_APTM_ODNO", ""); // 운용사지정주문번호 (빈값 허용)
        body.put("SLL_TYPE", order.direction() == Order.OrderDirection.SELL ? "00" : ""); // 매도=00, 매수=빈값
        body.put("ORD_SVR_DVSN_CD", "0");

        OrderResponse response = kisHttpClient.post(PATH, headers, body, OrderResponse.class);

        // rt_cd != "0" = KIS 비즈니스 오류 (HTTP 200이어도 실패) — msg_cd/msg1 포함해 예외 발생
        if (response == null || !"0".equals(response.rtCd())) {
            String code = response != null ? response.msgCd() : "N/A";
            String msg  = response != null ? response.msg1()  : "응답 없음";
            throw new RuntimeException("KIS 주문 실패 [" + code + "]: " + msg);
        }

        String odno = response.output() != null ? response.output().odno() : null;

        // id=null, accountId=null — KIS 응답 객체이므로 DB PK 없음, 호출자가 markPlaced()로 별도 처리
        return new Order(
                null, null, order.tradeDate(), order.ticker(), order.orderType(), order.direction(),
                order.quantity(), order.price(), Order.OrderStatus.PLACED, odno
        );
    }

    @Override
    public void cancel(Order order, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(CANCEL_TR_ID, account);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", account.accountNo());
        body.put("ACNT_PRDT_CD", account.kisAccountType());
        body.put("OVRS_EXCG_CD", order.ticker().getExchangeCode().name());
        body.put("PDNO", order.ticker().name());
        body.put("ORGN_ODNO", order.kisOrderId());  // 원주문번호 (PLACED 시 KIS가 부여)
        body.put("RVSE_CNCL_DVSN_CD", "02");        // 02=취소 (01=정정)
        body.put("ORD_QTY", "0");                    // 취소 시 0 고정
        body.put("OVRS_ORD_UNPR", "0");              // 취소 시 0 고정
        body.put("MGCO_APTM_ODNO", "");
        body.put("ORD_SVR_DVSN_CD", "0");

        kisHttpClient.post(CANCEL_PATH, headers, body, Void.class);
    }

    private String resolveOrderDvsn(Order.OrderType type) {
        return switch (type) {
            case LOC   -> "34"; // 장마감지정가(LOC)
            case MOC   -> "33"; // 장마감시장가(MOC)
            case LIMIT -> "00"; // 지정가
        };
    }

    private String resolvePrice(Order.OrderType type, BigDecimal price) {
        return KisResponseParser.formatPrice(type, price);
    }

    record OrderResponse(
            @JsonProperty("rt_cd")  String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1")   String msg1,
            @JsonProperty("output") Output output
    ) {
        record Output(@JsonProperty("ODNO") String odno) {}
    }
}
