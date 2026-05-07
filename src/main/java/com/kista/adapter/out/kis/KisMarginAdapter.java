package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Account;
import com.kista.domain.model.MarginItem;
import com.kista.domain.port.out.KisMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class KisMarginAdapter implements KisMarginPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/foreign-margin";
    private static final String TR_ID = "TTTC2101R"; // 해외증거금 통화별조회
    // USD·KRW 두 통화만 반환
    private static final Set<String> TARGET_CURRENCIES = Set.of("USD", "KRW");

    private final KisHttpClient kisHttpClient;

    @Override
    public List<MarginItem> getMargin(Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());

        MarginResponse response = kisHttpClient.get(PATH, headers, params, MarginResponse.class);

        if (response == null || response.output() == null) {
            return Collections.emptyList();
        }

        // crcy_cd 기준으로 USD·KRW 행만 필터링
        return response.output().stream()
                .filter(o -> TARGET_CURRENCIES.contains(o.crcyCd()))
                .map(o -> new MarginItem(
                        o.crcyCd(),
                        parseBd(o.itgrOrdPsblAmt()),
                        parseBd(o.frcrDnclAmt2())
                ))
                .toList();
    }

    private static BigDecimal parseBd(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    record MarginResponse(@JsonProperty("output") List<Output> output) {
        record Output(
                @JsonProperty("crcy_cd") String crcyCd,                   // 통화코드 (USD, KRW 등)
                @JsonProperty("itgr_ord_psbl_amt") String itgrOrdPsblAmt, // 통합주문가능금액
                @JsonProperty("frcr_dncl_amt_2") String frcrDnclAmt2      // 외화잔고
        ) {}
    }
}
