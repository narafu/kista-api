package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.strategy.Ticker;
import com.kista.domain.port.out.KisProfitPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisProfitAdapter implements KisProfitPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/inquire-period-profit";
    private static final String TR_ID = "TTTS3039R"; // 해외주식 기간손익
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;

    @Override
    public PeriodProfitResult getPeriodProfit(Account account, LocalDate from, LocalDate to) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
        params.add("OVRS_EXCG_CD", "NASD");  // 미국 전체
        params.add("NATN_CD", "");
        params.add("CRCY_CD", "USD");
        params.add("PDNO", "");              // 전종목
        params.add("INQR_STRT_DT", from.format(FMT));
        params.add("INQR_END_DT", to.format(FMT));
        params.add("WCRC_FRCR_DVSN_CD", "01"); // 01=외화
        params.add("CTX_AREA_FK200", "");
        params.add("CTX_AREA_NK200", "");

        ProfitResponse response = kisHttpClient.get(PATH, headers, params, ProfitResponse.class);

        if (response == null) {
            return new PeriodProfitResult(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<PeriodProfitResult.Item> items = response.output1() == null
                ? Collections.emptyList()
                : response.output1().stream()
                        .flatMap(o -> Ticker.tryParse(o.ovrsPdno())
                                .map(ticker -> new PeriodProfitResult.Item(
                                        o.tradDay(),
                                        ticker,
                                        KisResponseParser.parseIntSafe(o.slclQty()),
                                        KisResponseParser.parseBd(o.pchsAvgPric()),
                                        KisResponseParser.parseBd(o.avgSllUnpr()),
                                        KisResponseParser.parseBd(o.ovrsRlztPflsAmt()),
                                        KisResponseParser.parseBd(o.pftrt()),
                                        o.ovrsExcgCd()
                                ))
                                .stream()
                        )
                        .toList();

        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalRate = BigDecimal.ZERO;
        if (response.output2() != null) {
            totalProfit = KisResponseParser.parseBd(response.output2().ovrsRlztPflsTotAmt());
            totalRate = KisResponseParser.parseBd(response.output2().totPftrt());
        }
        return new PeriodProfitResult(items, totalProfit, totalRate);
    }

    record ProfitResponse(
            @JsonProperty("output1") List<Output1> output1,
            @JsonProperty("output2") Output2 output2
    ) {
        record Output1(
                @JsonProperty("trad_day") String tradDay,           // 매매일
                @JsonProperty("ovrs_pdno") String ovrsPdno,         // 해외상품번호
                @JsonProperty("slcl_qty") String slclQty,           // 매도청산수량
                @JsonProperty("pchs_avg_pric") String pchsAvgPric,  // 매입평균가격
                @JsonProperty("avg_sll_unpr") String avgSllUnpr,    // 평균매도단가
                @JsonProperty("ovrs_rlzt_pfls_amt") String ovrsRlztPflsAmt, // 실현손익
                @JsonProperty("pftrt") String pftrt,                // 수익률
                @JsonProperty("ovrs_excg_cd") String ovrsExcgCd    // 해외거래소코드
        ) {}

        record Output2(
                @JsonProperty("ovrs_rlzt_pfls_tot_amt") String ovrsRlztPflsTotAmt, // 총실현손익
                @JsonProperty("tot_pftrt") String totPftrt                          // 총수익률
        ) {}
    }
}
