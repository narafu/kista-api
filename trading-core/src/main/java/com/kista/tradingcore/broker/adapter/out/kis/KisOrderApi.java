package com.kista.tradingcore.broker.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelOutcome;
import com.kista.tradingcore.broker.domain.model.kis.KisApiException;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.shared.kernel.OrderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;


@Slf4j
@Component
@RequiredArgsConstructor
class KisOrderApi {

    private static final String PATH        = "/uapi/overseas-stock/v1/trading/order";
    private static final String CANCEL_PATH = "/uapi/overseas-stock/v1/trading/order-rvsecncl";
    private static final String BUY_TR_ID   = "TTTT1002U"; // 미국 해외주식 매수 주문
    private static final String SELL_TR_ID  = "TTTT1006U"; // 미국 해외주식 매도 주문
    private static final String CANCEL_TR_ID = "TTTT1004U"; // 미국 해외주식 정정취소주문

    // 잔고·수량 부족 msg_cd — APBK0988 주문수량이 가능수량보다 큼(BUY·SELL 공통), APBK0952 주문가능금액 초과(BUY, 2026-10-31 운영 실측)
    private static final Set<String> INSUFFICIENT_FUNDS_CODES = Set.of("APBK0988", "APBK0952");

    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;
    private final KisTradingApi kisTradingApi; // 취소 전 원주문 잔량 조회

    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        String trId = instruction.direction() == OrderDirection.BUY ? BUY_TR_ID : SELL_TR_ID;
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
                resolveOrderDvsn(instruction.orderType(), instruction.direction()),
                exchangeRegistry.ovrsExcgCd(instruction.ticker()),
                instruction.ticker().name(),
                KisResponseParser.formatPrice(instruction.orderType(), instruction.direction(), instruction.price()),
                instruction.quantity(),
                kisSllType(instruction.direction()));

        OrderResponse response = kisHttpClient.post(trId, PATH, account, body, OrderResponse.class);
        if (response == null) {
            throw new KisApiException("KIS 주문 응답 본문 없음 — 접수 여부 불명", null); // 처리 여부를 모르므로 결과 불명
        }

        // rt_cd != "0" = KIS 비즈니스 오류 (HTTP 200이어도 실패) — msg_cd/msg1 포함해 예외 발생
        if (!"0".equals(response.rtCd())) {
            String code = response.msgCd();
            String msg  = response.msg1();
            String failure = "KIS 주문 실패 [" + code + "]: " + msg;
            if (code != null && INSUFFICIENT_FUNDS_CODES.contains(code)) { // 잔고·수량 부족 확정 거절(msg_cd 누락은 일반 거절 — Set.of는 null 조회 시 NPE)
                throw KisApiException.rejectedInsufficientFunds(failure, null);
            }
            throw KisApiException.rejected(failure, null); // 응답 수신 후 거부 — 확정 거절
        }

        String odno = response.output() != null ? response.output().odno() : null;
        return new OrderResult(odno);
    }

    public CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account) {
        // 이미 종결(체결 완료·취소·장 마감 만료)된 주문은 취소 API를 부르지 않고 결과로 알린다 — 못 찾으면 그대로 취소 요청
        Optional<KisTradingApi.OrderStatusView> closed = findOrderOrEmpty(instruction, account)
                .filter(o -> o.ordered() > 0 && o.unfilled() == 0);
        if (closed.isPresent()) {
            return closed.get().filled() == closed.get().ordered() ? CancelOutcome.ALREADY_FILLED : CancelOutcome.ALREADY_CANCELED;
        }

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
                exchangeRegistry.ovrsExcgCd(instruction.ticker()),
                instruction.ticker().name(),
                instruction.externalOrderId());

        OrderResponse response = kisHttpClient.post(CANCEL_TR_ID, CANCEL_PATH, account, body, OrderResponse.class);
        if (response == null) {
            throw new KisApiException("KIS 취소 응답 본문 없음 — 취소 여부 불명", null);
        }
        // rt_cd != "0" = KIS 비즈니스 오류 (HTTP 200이어도 실패) — 무시하면 살아 있는 주문을 취소된 것으로 기록한다
        if (!"0".equals(response.rtCd())) {
            throw KisApiException.rejected("KIS 취소 실패 [" + response.msgCd() + "]: " + response.msg1(), null);
        }
        return CancelOutcome.CANCELLED;
    }

    // 사전 조회 실패는 "못 찾음"과 같이 취급 — 조회 장애로 살아 있는 주문의 취소가 막히면 애프터장 체결 누락이 다시 열린다
    private Optional<KisTradingApi.OrderStatusView> findOrderOrEmpty(CancelInstruction instruction, BrokerAccountRef account) {
        try {
            return kisTradingApi.findOrder(instruction.ticker(), instruction.externalOrderId(), account);
        } catch (RuntimeException e) {
            log.warn("KIS 취소 전 원주문 조회 실패 — 취소 요청으로 진행: odno={}, {}", instruction.externalOrderId(), e.getMessage());
            return Optional.empty();
        }
    }

    // KIS SLL_TYPE 파라미터: 매도=00, 매수="" (빈 문자열) — 옛 trading OrderDirection.kisSllType()에 있던 KIS 전용 인코딩을 broker로 이동
    private static String kisSllType(OrderDirection direction) {
        return direction == OrderDirection.SELL ? "00" : "";
    }

    // accountNo = "74420614-01" → [CANO, ACNT_PRDT_CD] 분리 (KisHttpClient 공용 헬퍼 위임)
    private static String[] splitAccountNo(BrokerAccountRef account) {
        return KisHttpClient.splitAccountNo(account.accountNo());
    }

    // KIS 미국 ORD_DVSN — MOO(31)/MOC(33)는 매도 전용이라 매수는 LOO(32)/LOC(34) 캡 지정가로 대체
    private String resolveOrderDvsn(OrderType type, OrderDirection direction) {
        boolean buy = direction == OrderDirection.BUY;
        return switch (type) {
            case LOC   -> "34";             // 장마감지정가(LOC)
            case MOC   -> buy ? "34" : "33"; // 장마감시장가(MOC) — 매수는 LOC
            case MOO   -> buy ? "32" : "31"; // 장개시시장가(MOO) — 매수는 LOO
            case LIMIT -> "00";             // 지정가
            case MARKET -> throw new IllegalArgumentException("MARKET 주문은 코인 거래소 전용입니다"); // 주식 경로 도달 불가 방어
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
