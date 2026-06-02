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
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<LocalDate> holidays = fetchHolidays(start, end);

        marketHolidayStorePort.replaceByYear(year, holidays);
        log.info("{}년 시장 캘린더 갱신 완료: 휴장일 {}일 적재", year, holidays.size());
    }

    // 해당 월 데이터만 최신화
    @Override
    public void refreshMonth(int year, int month) {
        log.info("{}년 {}월 시장 캘린더 갱신 시작 (Alpaca)", year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<LocalDate> holidays = fetchHolidays(start, end);

        marketHolidayStorePort.replaceByMonth(year, month, holidays);
        log.info("{}년 {}월 시장 캘린더 갱신 완료: 휴장일 {}일 적재", year, month, holidays.size());
    }

    // 지정 기간의 평일 중 Alpaca 거래일 목록에 없는 날 = 휴장일
    private List<LocalDate> fetchHolidays(LocalDate start, LocalDate end) {
        List<CalendarEntry> tradingDays = fetchTradingDays(start, end);
        Set<LocalDate> openDates = tradingDays.stream()
                .map(e -> LocalDate.parse(e.date()))
                .collect(Collectors.toSet());
        return start.datesUntil(end.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .filter(d -> !openDates.contains(d))
                .toList();
    }

    private List<CalendarEntry> fetchTradingDays(LocalDate start, LocalDate end) {
        String url = UriComponentsBuilder
                .fromHttpUrl(alpacaProperties.baseUrl() + CALENDAR_PATH)
                .queryParam("start", start.toString())
                .queryParam("end", end.toString())
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("APCA-API-KEY-ID", alpacaProperties.apiKey());
        headers.set("APCA-API-SECRET-KEY", alpacaProperties.apiSecret());

        var response = alpacaRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<CalendarEntry>>() {}
        );
        List<CalendarEntry> entries = response.getBody();
        log.info("{} ~ {} 거래일 {}건 수신", start, end, entries == null ? 0 : entries.size());
        return entries == null ? List.of() : entries;
    }

    // Alpaca GET /v2/calendar 응답 단일 항목
    record CalendarEntry(@JsonProperty("date") String date,
                         @JsonProperty("open") String open,
                         @JsonProperty("close") String close) {}
}
