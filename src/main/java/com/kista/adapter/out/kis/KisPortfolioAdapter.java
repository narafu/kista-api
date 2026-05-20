package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.Ticker;
import com.kista.domain.port.out.KisPortfolioPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisPortfolioAdapter implements KisPortfolioPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/inquire-present-balance";
    private static final String TR_ID = "CTRP6504R"; // 해외주식 체결기준현재잔고 (실전투자)

    private final KisHttpClient kisHttpClient;

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
        params.add("WCRC_FRCR_DVSN_CD", "02"); // 02=외화
        params.add("NATN_CD", "000");           // 000=전체
        params.add("TR_MKET_CD", "00");         // 00=전체
        params.add("INQR_DVSN_CD", "00");       // 00=전체

        BalanceResponse response = kisHttpClient.get(PATH, headers, params, BalanceResponse.class);

        if (response == null) {
            return new PresentBalanceResult(Collections.emptyList(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<PresentBalanceResult.Item> items = response.output1() == null
                ? Collections.emptyList()
                : response.output1().stream()
                        .flatMap(o -> Ticker.tryParse(o.pdno())
                                .map(ticker -> new PresentBalanceResult.Item(
                                        ticker,
                                        KisResponseParser.parseIntSafe(o.balanceQuantity13()),
                                        KisResponseParser.parseBd(o.avgUnpr3()),
                                        KisResponseParser.parseBd(o.ovrsNowPric1()),
                                        KisResponseParser.parseBd(o.frcrEvluAmt2()),
                                        KisResponseParser.parseBd(o.evluPflsAmt2()),
                                        KisResponseParser.parseBd(o.evluPflsRt1()),
                                        o.ovrsExcgCd()
                                ))
                                .stream()
                        )
                        .toList();

        BigDecimal totalAsset = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalRate = BigDecimal.ZERO;
        if (response.output3() != null) {
            totalAsset = KisResponseParser.parseBd(response.output3().totAsstAmt());
            totalProfit = KisResponseParser.parseBd(response.output3().totEvluPflsAmt());
            totalRate = KisResponseParser.parseBd(response.output3().evluErngRt1());
        }
        return new PresentBalanceResult(items, totalAsset, totalProfit, totalRate);
    }

    record BalanceResponse(
            @JsonProperty("output1") List<Output1> output1,
            @JsonProperty("output3") Output3 output3
    ) {
        record Output1(
                @JsonProperty("pdno") String pdno,                 // 종목코드
                @JsonProperty("cblc_qty13") String balanceQuantity13, // 잔고수량
                @JsonProperty("avg_unpr3") String avgUnpr3,        // 평균단가
                @JsonProperty("ovrs_now_pric1") String ovrsNowPric1, // 현재가
                @JsonProperty("frcr_evlu_amt2") String frcrEvluAmt2, // 외화평가금액
                @JsonProperty("evlu_pfls_amt2") String evluPflsAmt2, // 평가손익
                @JsonProperty("evlu_pfls_rt1") String evluPflsRt1,  // 평가손익율
                @JsonProperty("ovrs_excg_cd") String ovrsExcgCd    // 해외거래소코드
        ) {}

        record Output3(
                @JsonProperty("tot_asst_amt") String totAsstAmt,        // 총자산금액
                @JsonProperty("tot_evlu_pfls_amt") String totEvluPflsAmt, // 총평가손익
                @JsonProperty("evlu_erng_rt1") String evluErngRt1       // 총수익률
        ) {}
    }
}
