package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.KisExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisExecutionAdapter implements KisExecutionPort {

    private static final String PATH  = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    private static final String TR_ID = "TTTS3035R"; // 해외주식 체결 내역 조회
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;

    @Override
    public List<Execution> getExecutions(LocalDate date, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);
        String dateStr = date.format(FMT);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
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
                .flatMap(item -> Ticker.tryParse(item.pdno())
                        .map(ticker -> new Execution(
                                date,
                                ticker,
                                KisResponseParser.parseDirection(item.sllBuyDvsnCd()),
                                KisResponseParser.parseIntSafe(item.filledQuantity()),
                                KisResponseParser.parseBd(item.ftCcldUnpr3()),
                                KisResponseParser.parseBd(item.ccldAmt()),
                                item.odno()
                        ))
                        .stream()
                )
                .toList();
    }

    record ExecutionListResponse(@JsonProperty("output") List<OutputItem> output) {
        record OutputItem(
                @JsonProperty("pdno") String pdno,                     // 종목코드
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd, // 매도매수구분: 01=매도, 02=매수
                @JsonProperty("ft_ccld_qty") String filledQuantity,     // FT체결수량
                @JsonProperty("ft_ccld_unpr3") String ftCcldUnpr3,     // FT체결단가
                @JsonProperty("ft_ccld_amt3") String ccldAmt,          // FT체결금액
                @JsonProperty("odno") String odno                       // 주문번호
        ) {}
    }
}
