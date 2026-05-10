package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Account;
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
    private static final String MARGIN_PATH   = "/uapi/overseas-stock/v1/trading/foreign-margin";
    private static final String BALANCE_TR_ID = "TTTS3012R"; // 해외주식 잔고 조회
    private static final String MARGIN_TR_ID  = "TTTC2101R"; // 해외증거금 통화별조회

    private final KisHttpClient kisHttpClient;

    @Override
    public AccountBalance getBalance(Account account) {
        HoldingResult holding = fetchHolding(account.symbol(), account);
        BigDecimal usdDeposit = fetchMargin(account);

        BigDecimal avgPrice = holding.qty() > 0 ? holding.avgPrice() : null;
        return new AccountBalance(holding.qty(), avgPrice, usdDeposit);
    }

    private HoldingResult fetchHolding(String symbol, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(BALANCE_TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
        params.add("OVRS_EXCG_CD", "NASD");     // 실전 미국전체
        params.add("TR_CRCY_CD", "USD");
        params.add("CTX_AREA_FK200", "");
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

    // 해외증거금 통화별조회(TTTC2101R)에서 미국(USD) 행의 itgr_ord_psbl_amt 반환
    // frcr_dncl_amt_2(환전된 외화만)가 아닌 통합주문가능금액 사용 — 원화 자동 환전 케이스 포함
    private BigDecimal fetchMargin(Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(MARGIN_TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());

        MarginResponse response = kisHttpClient.get(MARGIN_PATH, headers, params, MarginResponse.class);

        if (response == null || response.output() == null) {
            return BigDecimal.ZERO;
        }
        return response.output().stream()
                .filter(o -> "미국".equals(o.natnName()))
                .findFirst()
                .map(o -> parseBigDecimalSafe(o.itgrOrdPsblAmt()))
                .orElse(BigDecimal.ZERO);
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

    record BalanceResponse(@JsonProperty("output1") List<Output1> output1) {
        record Output1(
                @JsonProperty("ovrs_pdno") String pdno,         // 해외상품번호
                @JsonProperty("ovrs_cblc_qty") String cblcQty,  // 해외잔고수량
                @JsonProperty("pchs_avg_pric") String pchsAvgPric // 매입평균가격
        ) {}
    }

    record MarginResponse(@JsonProperty("output") List<Output> output) {
        record Output(
                @JsonProperty("natn_name") String natnName,              // 국가명 ("미국" 등)
                @JsonProperty("itgr_ord_psbl_amt") String itgrOrdPsblAmt // 통합주문가능금액 (USD)
        ) {}
    }
}
