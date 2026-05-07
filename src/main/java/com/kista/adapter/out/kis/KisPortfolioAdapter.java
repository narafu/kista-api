package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Account;
import com.kista.domain.model.PresentBalanceResult;
import com.kista.domain.port.out.KisPortfolioPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

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
                        .map(o -> new PresentBalanceResult.Item(
                                o.pdno(),
                                parseIntSafe(o.cblcQty13()),
                                parseBd(o.avgUnpr3()),
                                parseBd(o.ovrsNowPric1()),
                                parseBd(o.frcrEvluAmt2()),
                                parseBd(o.evluPflsAmt2()),
                                parseBd(o.evluPflsRt1()),
                                o.ovrsExcgCd()
                        ))
                        .toList();

        BigDecimal totalAsset = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalRate = BigDecimal.ZERO;
        if (response.output3() != null) {
            totalAsset = parseBd(response.output3().totAsstAmt());
            totalProfit = parseBd(response.output3().totEvluPflsAmt());
            totalRate = parseBd(response.output3().evluErngRt1());
        }
        return new PresentBalanceResult(items, totalAsset, totalProfit, totalRate);
    }

    private static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : (int) Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal parseBd(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    record BalanceResponse(
            @JsonProperty("output1") List<Output1> output1,
            @JsonProperty("output3") Output3 output3
    ) {
        record Output1(
                @JsonProperty("pdno") String pdno,                 // 종목코드
                @JsonProperty("cblc_qty13") String cblcQty13,      // 잔고수량
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
