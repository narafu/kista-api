package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.port.out.KisPricePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class KisPriceAdapter implements KisPricePort {

    private static final String PATH  = "/uapi/overseas-price/v1/quotations/price";
    private static final String TR_ID = "HHDFS00000300";
    private static final String EXCD  = "NAS";

    private final KisHttpClient kisHttpClient;

    @Override
    public BigDecimal getPrice(String symbol) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        params.add("EXCD", EXCD);
        params.add("SYMB", symbol);

        PriceResponse response = kisHttpClient.get(PATH, headers, params, PriceResponse.class);

        if (response == null || response.output() == null || response.output().last() == null) {
            throw new IllegalStateException("가격 조회 실패: " + symbol);
        }
        return new BigDecimal(response.output().last());
    }

    record PriceResponse(@JsonProperty("output") Output output) {
        record Output(@JsonProperty("last") String last) {}
    }
}
