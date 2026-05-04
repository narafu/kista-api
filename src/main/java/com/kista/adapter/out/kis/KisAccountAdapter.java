package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.AccountBalance;
import com.kista.domain.port.out.KisAccountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KisAccountAdapter implements KisAccountPort {

    private static final String BALANCE_PATH = "/uapi/overseas-stock/v1/trading/inquire-balance";
    private static final String PRESENT_PATH  = "/uapi/overseas-stock/v1/trading/inquire-present-balance";
    private static final String BALANCE_TR_ID = "TTTS3012R"; // 해외주식 잔고 조회
    private static final String PRESENT_TR_ID = "CTRP6504R"; // 해외주식 현재 잔고(외화) 조회

    private final KisHttpClient kisHttpClient;

    @Override
    public AccountBalance getBalance(String token) {
        String symbol = kisHttpClient.props().symbol();
        HoldingResult holding = fetchHolding(token, symbol);
        PresentResult present  = fetchPresent(token);

        BigDecimal avgPrice = holding.qty() > 0 ? holding.avgPrice() : null;
        return new AccountBalance(holding.qty(), avgPrice, present.effectiveAmt(), present.usdDeposit());
    }

    private HoldingResult fetchHolding(String token, String symbol) {
        HttpHeaders headers = kisHttpClient.buildHeaders(token, BALANCE_TR_ID);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", kisHttpClient.props().accountNo());
        params.add("ACNT_PRDT_CD", kisHttpClient.props().accountType());
        params.add("OVRS_EXCG_CD", "NASD");     // 실전 미국전체
        params.add("TR_CRCY_CD", "USD");
        params.add("CTX_AREA_FK200", "");        // 최초 조회시 공란
        params.add("CTX_AREA_NK200", "");

        BalanceResponse response = kisHttpClient.get(BALANCE_PATH, headers, params, BalanceResponse.class);

        if (response == null || response.output1() == null) {
            return new HoldingResult(0, BigDecimal.ZERO);
        }
        return response.output1().stream()
                .filter(o -> symbol.equals(o.pdno()))
                .findFirst()
                .map(o -> new HoldingResult(
                        parseIntSafe(o.cblcQty()),
                        parseBigDecimalSafe(o.pchsAvgPric())))
                .orElse(new HoldingResult(0, BigDecimal.ZERO));
    }

    private PresentResult fetchPresent(String token) {
        HttpHeaders headers = kisHttpClient.buildHeaders(token, PRESENT_TR_ID);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", kisHttpClient.props().accountNo());
        params.add("ACNT_PRDT_CD", kisHttpClient.props().accountType());
        params.add("WCRC_FRCR_DVSN_CD", "02"); // 외화
        params.add("NATN_CD", "000");            // 전체
        params.add("TR_MKET_CD", "00");          // 전체
        params.add("INQR_DVSN_CD", "00");       // 전체

        PresentBalanceResponse response = kisHttpClient.get(PRESENT_PATH, headers, params, PresentBalanceResponse.class);

        if (response == null || response.output3() == null) {
            return new PresentResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        PresentBalanceResponse.Output3 out = response.output3();
        return new PresentResult(
                parseBigDecimalSafe(out.frcrEvluAmt()),
                parseBigDecimalSafe(out.frcrDnclAmt()));
    }

    private static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal parseBigDecimalSafe(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    record HoldingResult(int qty, BigDecimal avgPrice) {}
    record PresentResult(BigDecimal effectiveAmt, BigDecimal usdDeposit) {}

    record BalanceResponse(@JsonProperty("output1") List<Output1> output1) {
        record Output1(
                @JsonProperty("ovrs_pdno") String pdno,         // 해외상품번호
                @JsonProperty("ovrs_cblc_qty") String cblcQty,  // 해외잔고수량
                @JsonProperty("pchs_avg_pric") String pchsAvgPric // 매입평균가격
        ) {}
    }

    record PresentBalanceResponse(@JsonProperty("output3") Output3 output3) {
        record Output3(
                @JsonProperty("frcr_evlu_amt2") String frcrEvluAmt,   // 유가증권평가액
                @JsonProperty("frcr_dncl_amt_2") String frcrDnclAmt   // 외화예수금
        ) {}
    }
}
