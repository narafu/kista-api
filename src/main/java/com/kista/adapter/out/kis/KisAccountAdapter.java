package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Account;
import com.kista.domain.model.AccountBalance;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.KisMarginPort;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisAccountAdapter implements KisAccountPort {

    private static final String BALANCE_PATH  = "/uapi/overseas-stock/v1/trading/inquire-balance";
    private static final String BALANCE_TR_ID = "TTTS3012R"; // 해외주식 잔고 조회

    private final KisHttpClient kisHttpClient;
    private final KisMarginPort kisMarginPort;

    @Override
    public AccountBalance getBalance(Account account) {
        HoldingResult holding = fetchHolding(account);
        BigDecimal usdDeposit = fetchMargin(account);

        BigDecimal avgPrice = holding.qty() > 0 ? holding.avgPrice() : null;
        return new AccountBalance(holding.qty(), avgPrice, usdDeposit);
    }

    private HoldingResult fetchHolding(Account account) {
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
                .filter(o -> account.ticker().name().equals(o.pdno()))
                .findFirst()
                .map(o -> new HoldingResult(
                        KisResponseParser.parseIntSafe(o.cblcQty()),
                        KisResponseParser.parseBd(o.pchsAvgPric())))
                .orElse(new HoldingResult(0, BigDecimal.ZERO));
    }

    // 해외증거금 통화별조회(TTTC2101R)에서 USD 행의 integratedOrderableAmount 반환
    // frcr_dncl_amt_2(환전된 외화만)가 아닌 통합주문가능금액 사용 — 원화 자동 환전 케이스 포함
    private BigDecimal fetchMargin(Account account) {
        return kisMarginPort.getMargin(account).stream()
                .filter(item -> "USD".equals(item.currency()))
                .findFirst()
                .map(item -> item.integratedOrderableAmount())
                .orElse(BigDecimal.ZERO);
    }

    record HoldingResult(int qty, BigDecimal avgPrice) {}

    record BalanceResponse(@JsonProperty("output1") List<Output1> output1) {
        record Output1(
                @JsonProperty("ovrs_pdno") String pdno,           // 해외상품번호
                @JsonProperty("ovrs_cblc_qty") String cblcQty,    // 해외잔고수량
                @JsonProperty("pchs_avg_pric") String pchsAvgPric // 매입평균가격
        ) {}
    }
}
