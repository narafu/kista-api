package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Execution;
import com.kista.domain.model.Order;
import com.kista.domain.port.out.KisExecutionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KisExecutionAdapter implements KisExecutionPort {

    private static final String PATH  = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    private static final String TR_ID = "TTTS3035R"; // 해외주식 체결 내역 조회
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;

    @Override
    public List<Execution> getExecutions(LocalDate date) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID);
        String dateStr = date.format(FMT);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", kisHttpClient.props().accountNo());
        params.add("ACNT_PRDT_CD", kisHttpClient.props().accountType());
        params.add("PDNO", "%");              // 전종목
        params.add("ORD_STRT_DT", dateStr);
        params.add("ORD_END_DT", dateStr);
        params.add("SLL_BUY_DVSN", "00");    // 전체
        params.add("CCLD_NCCS_DVSN", "00");  // 전체
        params.add("OVRS_EXCG_CD", "NASD");  // 미국 전체
        params.add("SORT_SQN", "DS");        // 정순
        params.add("ORD_DT", "");
        params.add("ORD_GNO_BRNO", "");
        params.add("ODNO", "");
        params.add("CTX_AREA_NK200", "");
        params.add("CTX_AREA_FK200", "");

        ExecutionListResponse response = kisHttpClient.get(PATH, headers, params, ExecutionListResponse.class);

        if (response == null || response.output() == null) {
            return Collections.emptyList();
        }
        return response.output().stream()
                .map(item -> new Execution(
                        date,
                        item.pdno(),
                        resolveDirection(item.sllBuyDvsnCd()),
                        parseIntSafe(item.ftCcldQty()),
                        parseBigDecimalSafe(item.ftCcldUnpr3()),
                        parseBigDecimalSafe(item.ccldAmt()),
                        item.odno()
                ))
                .toList();
    }

    private Order.OrderDirection resolveDirection(String sllBuyDvsnCd) {
        // sll_buy_dvsn_cd: 01=매도, 02=매수
        return "01".equals(sllBuyDvsnCd) ? Order.OrderDirection.SELL : Order.OrderDirection.BUY;
    }

    private static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal parseBigDecimalSafe(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    record ExecutionListResponse(@JsonProperty("output") List<OutputItem> output) {
        record OutputItem(
                @JsonProperty("pdno") String pdno,                     // 종목코드
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd, // 매도매수구분: 01=매도, 02=매수
                @JsonProperty("ft_ccld_qty") String ftCcldQty,         // FT체결수량
                @JsonProperty("ft_ccld_unpr3") String ftCcldUnpr3,     // FT체결단가
                @JsonProperty("ft_ccld_amt3") String ccldAmt,          // FT체결금액
                @JsonProperty("odno") String odno                       // 주문번호
        ) {}
    }
}
