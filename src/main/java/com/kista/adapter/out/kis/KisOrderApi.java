package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
class KisOrderApi {

    private static final String PATH        = "/uapi/overseas-stock/v1/trading/order";
    private static final String CANCEL_PATH = "/uapi/overseas-stock/v1/trading/order-rvsecncl";
    private static final String BUY_TR_ID   = "TTTT1002U"; // 미국 해외주식 매수 주문
    private static final String SELL_TR_ID  = "TTTT1006U"; // 미국 해외주식 매도 주문
    private static final String CANCEL_TR_ID = "TTTT1004U"; // 미국 해외주식 정정취소주문

    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;

    public Order place(Order order, Account account) {
        String trId = order.direction() == Order.OrderDirection.BUY ? BUY_TR_ID : SELL_TR_ID;
        String[] acctParts = splitAccountNo(account);
        String cano = acctParts[0];
        String acntPrdtCd = acctParts[1];

        // autotrade 성공 패턴과 동일한 필드 순서 및 raw JSON String 포맷으로 전송
        String body = """
                {
                    "ORD_SVR_DVSN_CD": "0",
                    "CANO": "%s",
                    "ACNT_PRDT_CD": "%s",
                    "ORD_DVSN": "%s",
                    "OVRS_EXCG_CD": "%s",
                    "PDNO": "%s",
                    "OVRS_ORD_UNPR": "%s",
                    "ORD_QTY": "%s",
                    "SLL_TYPE": "%s",
                    "CTAC_TLNO": "",
                    "MGCO_APTM_ODNO": ""
                }""".formatted(
                cano,
                acntPrdtCd,
                resolveOrderDvsn(order.orderType()),
                exchangeRegistry.ovrsExcgCd(order.ticker()),
                order.ticker().name(),
                KisResponseParser.formatPrice(order.orderType(), order.price()),
                order.quantity(),
                order.direction().kisSllType());

        OrderResponse response = kisHttpClient.post(trId, PATH, account, body, OrderResponse.class);

        // rt_cd != "0" = KIS 비즈니스 오류 (HTTP 200이어도 실패) — msg_cd/msg1 포함해 예외 발생
        if (response == null || !"0".equals(response.rtCd())) {
            String code = response != null ? response.msgCd() : "N/A";
            String msg  = response != null ? response.msg1()  : "응답 없음";
            throw new KisApiException("KIS 주문 실패 [" + code + "]: " + msg, null);
        }

        String odno = response.output() != null ? response.output().odno() : null;

        // id=null, accountId=null, strategyCycleId=null — KIS 응답 객체이므로 DB PK 없음, 호출자가 markPlaced()로 별도 처리
        return order.withPlaced(odno);
    }

    public void cancel(Order order, Account account) {
        String[] acctParts = splitAccountNo(account);
        String cano = acctParts[0];
        String acntPrdtCd = acctParts[1];

        // place()와 동일하게 raw JSON String으로 전송 — Map+Jackson 직렬화 시 EGW00202 발생
        String body = """
                {
                    "CANO": "%s",
                    "ACNT_PRDT_CD": "%s",
                    "OVRS_EXCG_CD": "%s",
                    "PDNO": "%s",
                    "ORGN_ODNO": "%s",
                    "RVSE_CNCL_DVSN_CD": "02",
                    "ORD_QTY": "0",
                    "OVRS_ORD_UNPR": "0",
                    "MGCO_APTM_ODNO": "",
                    "ORD_SVR_DVSN_CD": "0"
                }""".formatted(
                cano,
                acntPrdtCd,
                exchangeRegistry.ovrsExcgCd(order.ticker()),
                order.ticker().name(),
                order.externalOrderId());

        kisHttpClient.post(CANCEL_TR_ID, CANCEL_PATH, account, body, Void.class);
    }

    // accountNo = "74420614-01" → [CANO, ACNT_PRDT_CD] 분리 (KisHttpClient 공용 헬퍼 위임)
    private static String[] splitAccountNo(Account account) {
        return KisHttpClient.splitAccountNo(account.accountNo());
    }

    private String resolveOrderDvsn(Order.OrderType type) {
        return switch (type) {
            case LOC   -> "34"; // 장마감지정가(LOC)
            case MOC   -> "33"; // 장마감시장가(MOC)
            case LIMIT -> "00"; // 지정가
        };
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
