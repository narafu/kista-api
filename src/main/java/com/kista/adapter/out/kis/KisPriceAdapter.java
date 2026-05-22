package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.KisPricePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KisPriceAdapter implements KisPricePort {

    private static final String PATH       = "/uapi/overseas-price/v1/quotations/price";
    private static final String MULTI_PATH = "/uapi/overseas-price/v1/quotations/inquire-asking-multprice";
    private static final String TR_ID      = "HHDFS00000300";
    private static final String MULTI_TR_ID = "HHDFS76410000";
    private static final String EXCD       = "NAS";
    private static final int    MAX_TICKERS = 10;

    private final KisHttpClient kisHttpClient;

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        params.add("EXCD", EXCD);
        params.add("SYMB", ticker.name());

        PriceResponse response = kisHttpClient.get(PATH, headers, params, PriceResponse.class);

        if (response == null || response.output() == null || response.output().last() == null) {
            throw new IllegalStateException("가격 조회 실패: " + ticker);
        }
        return new BigDecimal(response.output().last());
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(MULTI_TR_ID, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");

        // KIS 슬롯은 1~10, 사용하지 않는 슬롯은 빈 값으로 채움
        for (int i = 1; i <= MAX_TICKERS; i++) {
            if (i <= tickers.size()) {
                Ticker t = tickers.get(i - 1);
                // KIS exchangeCode: NASD → NAS, AMS → AMS
                String excd = t.getExchangeCode().equals("NASD") ? "NAS" : t.getExchangeCode();
                params.add("EXCD" + i, excd);
                params.add("SYMB" + i, t.name());
            } else {
                params.add("EXCD" + i, "");
                params.add("SYMB" + i, "");
            }
        }

        MultiPriceResponse response = kisHttpClient.get(MULTI_PATH, headers, params, MultiPriceResponse.class);
        if (response == null) {
            throw new IllegalStateException("복수 종목 가격 조회 실패");
        }

        // output1~10에서 유효한 last 값 추출 후 ticker와 매핑
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        List<MultiPriceResponse.Output> outputs = response.toList();
        for (int i = 0; i < outputs.size() && i < tickers.size(); i++) {
            String last = outputs.get(i).last();
            if (last != null && !last.isBlank() && !last.equals("0")) {
                result.put(tickers.get(i), new BigDecimal(last));
            }
        }
        return result;
    }

    record PriceResponse(@JsonProperty("output") Output output) {
        record Output(@JsonProperty("last") String last) {}
    }

    // KIS HHDFS76410000 응답: output1~output10 각 슬롯에 종목 현재가
    record MultiPriceResponse(
            @JsonProperty("output1") Output output1,
            @JsonProperty("output2") Output output2,
            @JsonProperty("output3") Output output3,
            @JsonProperty("output4") Output output4,
            @JsonProperty("output5") Output output5,
            @JsonProperty("output6") Output output6,
            @JsonProperty("output7") Output output7,
            @JsonProperty("output8") Output output8,
            @JsonProperty("output9") Output output9,
            @JsonProperty("output10") Output output10
    ) {
        record Output(@JsonProperty("last") String last) {}

        java.util.List<Output> toList() {
            return java.util.stream.Stream.of(
                    output1, output2, output3, output4, output5,
                    output6, output7, output8, output9, output10
            ).filter(o -> o != null).toList();
        }
    }
}
