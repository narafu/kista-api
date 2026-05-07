package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Account;
import com.kista.domain.model.Order;
import com.kista.domain.model.ReservationOrder;
import com.kista.domain.model.ReservationOrderCommand;
import com.kista.domain.model.ReservationOrderReceipt;
import com.kista.domain.port.out.KisReservationOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KisReservationOrderAdapter implements KisReservationOrderPort {

    private static final String LIST_PATH  = "/uapi/overseas-stock/v1/trading/order-resv-list";
    private static final String ORDER_PATH = "/uapi/overseas-stock/v1/trading/order-resv";
    private static final String LIST_TR_ID = "TTTT3039R";  // 미국 예약주문 조회 (실전)
    private static final String BUY_TR_ID  = "TTTT3014U";  // 미국 예약주문 매수 (실전)
    private static final String SELL_TR_ID = "TTTT3016U";  // 미국 예약주문 매도 (실전)
    private static final String EXCHANGE_CODE = "NASD";     // 나스닥 (미국 전체)
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;

    @Override
    public List<ReservationOrder> getReservationOrders(LocalDate from, LocalDate to, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(LIST_TR_ID, account);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
        params.add("INQR_STRT_DT", from.format(FMT)); // 조회시작일자
        params.add("INQR_END_DT", to.format(FMT));     // 조회종료일자
        params.add("INQR_DVSN_CD", "00");              // 00=전체
        params.add("OVRS_EXCG_CD", EXCHANGE_CODE);
        params.add("PRDT_TYPE_CD", "");
        params.add("CTX_AREA_FK200", "");
        params.add("CTX_AREA_NK200", "");

        ReservationListResponse response = kisHttpClient.get(LIST_PATH, headers, params, ReservationListResponse.class);

        if (response == null || response.output() == null) {
            return Collections.emptyList();
        }

        return response.output().stream()
                .map(o -> new ReservationOrder(
                        o.rsvnOrdRcitDt(),
                        o.ordRcitTmd(),
                        o.ovrsRsvnOdno(),
                        parseDirection(o.sllBuyDvsnCd()),
                        o.ovrsRsvnOrdStatCd(),
                        o.ovrsRsvnOrdStatCdName(),
                        o.pdno(),
                        o.prdtName(),
                        o.ovrsExcgCd(),
                        parseIntSafe(o.ftOrdQty()),
                        parseBd(o.ftOrdUnpr3()),
                        parseIntSafe(o.ftCcldQty()),
                        "Y".equals(o.cnclYn())
                ))
                .toList();
    }

    @Override
    public ReservationOrderReceipt placeReservationOrder(ReservationOrderCommand command, Account account) {
        // 매수/매도에 따라 TR_ID 분기
        String trId = command.direction() == Order.OrderDirection.BUY ? BUY_TR_ID : SELL_TR_ID;
        HttpHeaders headers = kisHttpClient.buildHeaders(trId, account);

        Map<String, String> body = new HashMap<>();
        body.put("CANO", account.accountNo());
        body.put("ACNT_PRDT_CD", account.kisAccountType());
        body.put("PDNO", command.symbol());
        body.put("OVRS_EXCG_CD", EXCHANGE_CODE);
        body.put("FT_ORD_QTY", String.valueOf(command.qty()));
        body.put("FT_ORD_UNPR3", command.price().toPlainString());

        ReservationOrderResponse response = kisHttpClient.post(ORDER_PATH, headers, body, ReservationOrderResponse.class);

        String kisOrderId = "";
        String reservationOrderId = "";
        String receiptDate = "";
        if (response != null && response.output() != null) {
            kisOrderId = response.output().odno() != null ? response.output().odno() : "";
            reservationOrderId = response.output().ovrsRsvnOdno() != null ? response.output().ovrsRsvnOdno() : "";
            receiptDate = response.output().rsvnOrdRcitDt() != null ? response.output().rsvnOrdRcitDt() : "";
        }
        return new ReservationOrderReceipt(kisOrderId, reservationOrderId, receiptDate);
    }

    // sll_buy_dvsn_cd: 01=매도, 02=매수
    private static Order.OrderDirection parseDirection(String code) {
        return "01".equals(code) ? Order.OrderDirection.SELL : Order.OrderDirection.BUY;
    }

    private static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : (int) Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal parseBd(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    record ReservationListResponse(@JsonProperty("output") List<Output> output) {
        record Output(
                @JsonProperty("rsvn_ord_rcit_dt") String rsvnOrdRcitDt,              // 예약주문접수일자
                @JsonProperty("ord_rcit_tmd") String ordRcitTmd,                      // 주문접수시각
                @JsonProperty("ovrs_rsvn_odno") String ovrsRsvnOdno,                  // 해외예약주문번호
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd,                 // 매도매수구분코드
                @JsonProperty("ovrs_rsvn_ord_stat_cd") String ovrsRsvnOrdStatCd,      // 상태코드
                @JsonProperty("ovrs_rsvn_ord_stat_cd_name") String ovrsRsvnOrdStatCdName, // 상태명
                @JsonProperty("pdno") String pdno,                                     // 종목코드
                @JsonProperty("prdt_name") String prdtName,                            // 상품명
                @JsonProperty("ovrs_excg_cd") String ovrsExcgCd,                       // 거래소코드
                @JsonProperty("ft_ord_qty") String ftOrdQty,                           // FT주문수량
                @JsonProperty("ft_ord_unpr3") String ftOrdUnpr3,                       // FT주문단가
                @JsonProperty("ft_ccld_qty") String ftCcldQty,                         // FT체결수량
                @JsonProperty("cncl_yn") String cnclYn                                 // 취소여부 (Y=취소)
        ) {}
    }

    record ReservationOrderResponse(@JsonProperty("output") Output output) {
        record Output(
                @JsonProperty("ODNO") String odno,                        // 주문번호
                @JsonProperty("RSVN_ORD_RCIT_DT") String rsvnOrdRcitDt, // 예약주문접수일자
                @JsonProperty("OVRS_RSVN_ODNO") String ovrsRsvnOdno     // 해외예약주문번호
        ) {}
    }
}
