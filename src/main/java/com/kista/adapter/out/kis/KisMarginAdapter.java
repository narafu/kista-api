package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.port.out.KisMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KisMarginAdapter implements KisMarginPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/foreign-margin";
    private static final String TR_ID = "TTTC2101R"; // 해외증거금 통화별조회
    private static final String TARGET_NATION = "미국";

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

        // natn_name == "미국" 행만 필터링 — crcy_cd 기준 시 동일 itgr_ord_psbl_amt 중복 행 발생
        return response.output().stream()
                .filter(o -> TARGET_NATION.equals(o.natnName()))
                .map(o -> new MarginItem(
                        o.crcyCd(),
                        KisResponseParser.parseBd(o.itgrOrdPsblAmt())
                ))
                .toList();
    }

    record MarginResponse(@JsonProperty("output") List<Output> output) {
        record Output(
                @JsonProperty("natn_name") String natnName,               // 국가명 (미국, 일본 등)
                @JsonProperty("crcy_cd") String crcyCd,                   // 통화코드 (USD 등)
                @JsonProperty("itgr_ord_psbl_amt") String itgrOrdPsblAmt  // 통합주문가능금액
        ) {}
    }
}
