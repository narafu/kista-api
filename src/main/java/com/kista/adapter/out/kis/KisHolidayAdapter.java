package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.port.out.KisHolidayPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisHolidayAdapter implements KisHolidayPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/countries-holiday";
    private static final String TR_ID = "CTOS5011R";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisHttpClient kisHttpClient;

    @Override
    public boolean isMarketOpen(LocalDate date) {
        try {
            HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID);
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("BASS_DT", date.format(FMT));
            params.add("CTX_AREA_KEY", "");
            params.add("CTX_AREA_NK", "");

            HolidayResponse response = kisHttpClient.get(PATH, headers, params, HolidayResponse.class);
            // output[]가 비어있으면 해당 날짜는 거래일
            return response == null || response.output() == null || response.output().isEmpty();
        } catch (Exception e) {
            // 오류 시 개장으로 폴백 — 로그로 진단 가능하게 유지
            log.warn("시장 개장 여부 조회 실패, 개장으로 폴백: {}", e.getMessage());
            return true;
        }
    }

    record HolidayResponse(@JsonProperty("output") List<Object> output) {}
}
