package com.kista.adapter.out.alpaca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.port.out.MarketHolidayStorePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaCalendarAdapterTest {

    @Test
    @SuppressWarnings("unchecked")
    void refreshMonth_거래일_목록에_없는_평일을_휴장일로_저장한다() throws Exception {
        RestTemplate restTemplate = new AlpacaConfig().alpacaRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AlpacaProperties properties = new AlpacaProperties("https://paper-api.alpaca.markets", "test-key", "test-secret", "https://data.test");
        MarketHolidayStorePort holidayStorePort = mock(MarketHolidayStorePort.class);
        AlpacaCalendarAdapter adapter = new AlpacaCalendarAdapter(restTemplate, properties, holidayStorePort);

        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        // 신정(1/1)·MLK 데이(1/19)를 제외한 나머지 평일만 Alpaca 거래일로 응답
        List<LocalDate> expectedHolidays = List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19));
        List<Map<String, String>> tradingDays = new ArrayList<>();
        start.datesUntil(end.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .filter(d -> !expectedHolidays.contains(d))
                .forEach(d -> tradingDays.add(Map.of("date", d.toString(), "open", "09:30", "close", "16:00")));
        String responseBody = new ObjectMapper().writeValueAsString(tradingDays);

        server.expect(requestTo("https://paper-api.alpaca.markets/v2/calendar?start=2026-01-01&end=2026-01-31"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("APCA-API-KEY-ID", "test-key"))
                .andExpect(header("APCA-API-SECRET-KEY", "test-secret"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        adapter.refreshMonth(2026, 1);

        // 평일 중 거래일 응답에 없는 날짜만 휴장일로 저장 위임되는지 캡처 검증
        ArgumentCaptor<List<LocalDate>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayStorePort).replaceByMonth(eq(2026), eq(1), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrderElementsOf(expectedHolidays);
        server.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 거래일_응답이_비어있으면_해당_월_평일_전체를_휴장일로_처리한다() {
        RestTemplate restTemplate = new AlpacaConfig().alpacaRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AlpacaProperties properties = new AlpacaProperties("https://paper-api.alpaca.markets", "test-key", "test-secret", "https://data.test");
        MarketHolidayStorePort holidayStorePort = mock(MarketHolidayStorePort.class);
        AlpacaCalendarAdapter adapter = new AlpacaCalendarAdapter(restTemplate, properties, holidayStorePort);

        // 거래일 응답 자체가 빈 배열인 엣지 케이스 — null 대신 빈 리스트로 파싱되어야 함
        server.expect(requestTo("https://paper-api.alpaca.markets/v2/calendar?start=2026-02-01&end=2026-02-28"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        adapter.refreshMonth(2026, 2);

        long expectedWeekdayCount = LocalDate.of(2026, 2, 1).datesUntil(LocalDate.of(2026, 3, 1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();

        ArgumentCaptor<List<LocalDate>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayStorePort).replaceByMonth(eq(2026), eq(2), captor.capture());
        assertThat(captor.getValue()).hasSize((int) expectedWeekdayCount);
        server.verify();
    }
}
