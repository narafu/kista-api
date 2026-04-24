package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.port.out.KisHolidayPort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class KisHolidayAdapter implements KisHolidayPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/countries-holiday";
    private static final String TR_ID = "CTOS5011R";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisHttpClient kisHttpClient;

    public KisHolidayAdapter(KisHttpClient kisHttpClient) {
        this.kisHttpClient = kisHttpClient;
    }

    @Override
    public boolean isMarketOpen(String token, LocalDate date) {
        try {
            HttpHeaders headers = kisHttpClient.buildHeaders(token, TR_ID);
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("BASS_DT", date.format(FMT));
            params.add("CTX_AREA_KEY", "");
            params.add("CTX_AREA_NK", "");

            HolidayResponse response = kisHttpClient.get(PATH, headers, params, HolidayResponse.class);
            // output[]가 비어있으면 해당 날짜는 거래일
            return response == null || response.output() == null || response.output().isEmpty();
        } catch (Exception e) {
            return true; // 오류 시 개장으로 폴백
        }
    }

    record HolidayResponse(@JsonProperty("output") List<Object> output) {}
}
