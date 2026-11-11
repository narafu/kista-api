package com.kista.tradingcore.marketcalendar.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarRefreshPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketHolidayStorePort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketCalendarTradingDay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlpacaCalendarAdapter implements MarketCalendarRefreshPort {

    private static final String CALENDAR_PATH = "/v2/calendar";

    private final RestClient marketAlpacaRestClient;
    private final AlpacaProperties alpacaProperties;
    private final MarketHolidayStorePort marketHolidayStorePort; // DB 저장 위임

    @Override
    public void refreshCalendar(int year) {
        log.info("{}년 시장 캘린더 갱신 시작 (Alpaca)", year);
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<MarketCalendarTradingDay> tradingDays = fetchTradingDays(start, end);

        marketHolidayStorePort.replaceSessionsByYear(year, tradingDays);
        log.info("{}년 시장 캘린더 갱신 완료: 거래일 {}일 적재", year, tradingDays.size());
    }

    // 해당 월 데이터만 최신화
    @Override
    public void refreshMonth(int year, int month) {
        log.info("{}년 {}월 시장 캘린더 갱신 시작 (Alpaca)", year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<MarketCalendarTradingDay> tradingDays = fetchTradingDays(start, end);

        marketHolidayStorePort.replaceSessionsByMonth(year, month, tradingDays);
        log.info("{}년 {}월 시장 캘린더 갱신 완료: 거래일 {}일 적재", year, month, tradingDays.size());
    }

    private List<MarketCalendarTradingDay> fetchTradingDays(LocalDate start, LocalDate end) {
        String url = UriComponentsBuilder
                .fromPath(CALENDAR_PATH)
                .queryParam("start", start.toString())
                .queryParam("end", end.toString())
                .toUriString();

        List<CalendarEntry> entries = marketAlpacaRestClient.get()
                .uri(url)
                .header("APCA-API-KEY-ID", alpacaProperties.apiKey())
                .header("APCA-API-SECRET-KEY", alpacaProperties.apiSecret())
                .retrieve()
                .body(new ParameterizedTypeReference<List<CalendarEntry>>() {});
        log.info("{} ~ {} 거래일 {}건 수신", start, end, entries == null ? 0 : entries.size());
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException("빈 시장 캘린더 응답: " + start + " ~ " + end);
        }
        Set<LocalDate> dates = new HashSet<>();
        return entries.stream().map(entry -> {
            try {
                if (entry == null || entry.date() == null || entry.open() == null || entry.close() == null) {
                    throw new IllegalStateException("시장 캘린더 일자 또는 시각 누락");
                }
                LocalDate date = LocalDate.parse(entry.date());
                LocalTime open = LocalTime.parse(entry.open());
                LocalTime close = LocalTime.parse(entry.close());
                if (date.isBefore(start) || date.isAfter(end) || date.getDayOfWeek() == DayOfWeek.SATURDAY
                        || date.getDayOfWeek() == DayOfWeek.SUNDAY || !dates.add(date) || !open.isBefore(close)) {
                    throw new IllegalStateException("잘못된 시장 캘린더 항목: " + entry);
                }
                return new MarketCalendarTradingDay(date, open, close);
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("잘못된 시장 캘린더 일자 또는 시각: " + entry, e);
            }
        }).toList();
    }

    // Alpaca GET /v2/calendar 응답 단일 항목
    record CalendarEntry(@JsonProperty("date") String date,
                         @JsonProperty("open") String open,
                         @JsonProperty("close") String close) {}
}
