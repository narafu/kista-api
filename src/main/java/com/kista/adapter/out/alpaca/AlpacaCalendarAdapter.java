package com.kista.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.port.out.MarketCalendarRefreshPort;
import com.kista.domain.port.out.MarketHolidayStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlpacaCalendarAdapter implements MarketCalendarRefreshPort {

    private static final String CALENDAR_PATH = "/v2/calendar";

    private final RestTemplate alpacaRestTemplate;
    private final AlpacaProperties alpacaProperties;
    private final MarketHolidayStorePort marketHolidayStorePort; // DB 저장 위임

    // Alpaca Calendar API → 평일 중 거래 없는 날(휴장일)을 DB에 갱신
    @Override
    public void refreshCalendar(int year) {
        log.info("{}년 시장 캘린더 갱신 시작 (Alpaca)", year);
        List<CalendarEntry> tradingDays = fetchTradingDays(year);
        List<LocalDate> holidays = computeHolidays(year, tradingDays);

        marketHolidayStorePort.replaceByYear(year, holidays);
        log.info("{}년 시장 캘린더 갱신 완료: 휴장일 {}일 적재", year, holidays.size());
    }

    private List<CalendarEntry> fetchTradingDays(int year) {
        String url = UriComponentsBuilder
                .fromHttpUrl(alpacaProperties.baseUrl() + CALENDAR_PATH)
                .queryParam("start", year + "-01-01")
                .queryParam("end", year + "-12-31")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("APCA-API-KEY-ID", alpacaProperties.apiKey());
        headers.set("APCA-API-SECRET-KEY", alpacaProperties.apiSecret());

        var response = alpacaRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<CalendarEntry>>() {}
        );
        List<CalendarEntry> entries = response.getBody();
        log.info("{}년 거래일 {}건 수신", year, entries == null ? 0 : entries.size());
        return entries == null ? List.of() : entries;
    }

    // 해당 연도 평일 중 Alpaca 거래일 목록에 없는 날 = 휴장일
    private List<LocalDate> computeHolidays(int year, List<CalendarEntry> tradingDays) {
        Set<LocalDate> openDates = tradingDays.stream()
                .map(e -> LocalDate.parse(e.date()))
                .collect(Collectors.toSet());

        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        return start.datesUntil(end.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .filter(d -> !openDates.contains(d))
                .toList();
    }

    // Alpaca GET /v2/calendar 응답 단일 항목
    record CalendarEntry(@JsonProperty("date") String date,
                         @JsonProperty("open") String open,
                         @JsonProperty("close") String close) {}
}
