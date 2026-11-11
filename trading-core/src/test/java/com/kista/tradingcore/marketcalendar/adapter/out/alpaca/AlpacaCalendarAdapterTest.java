package com.kista.tradingcore.marketcalendar.adapter.out.alpaca;

import tools.jackson.databind.ObjectMapper;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketHolidayStorePort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketCalendarTradingDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaCalendarAdapterTest {

    @Test
    @SuppressWarnings("unchecked")
    void refreshMonth_거래일과_실제_시각을_저장한다() throws Exception {
        // baseUrl은 이제 RestClient(=AlpacaConfig 빈)에 고정 — 테스트도 동일하게 builder에 설정
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://paper-api.alpaca.markets");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaProperties properties = new AlpacaProperties("https://paper-api.alpaca.markets", "test-key", "test-secret", "https://data.test");
        MarketHolidayStorePort holidayStorePort = mock(MarketHolidayStorePort.class);
        AlpacaCalendarAdapter adapter = new AlpacaCalendarAdapter(builder.build(), properties, holidayStorePort);

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

        ArgumentCaptor<List<MarketCalendarTradingDay>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayStorePort).replaceSessionsByMonth(eq(2026), eq(1), captor.capture());
        assertThat(captor.getValue()).hasSize(tradingDays.size())
                .contains(new MarketCalendarTradingDay(LocalDate.of(2026, 1, 2), LocalTime.of(9, 30), LocalTime.of(16, 0)))
                .extracting(MarketCalendarTradingDay::date)
                .doesNotContainAnyElementsOf(expectedHolidays);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[null]",
            "[{\"date\":\"2026-02-02\",\"open\":\"09:30\"}]",
            "[{\"date\":\"2026-02-30\",\"open\":\"09:30\",\"close\":\"16:00\"}]",
            "[{\"date\":\"2026-03-02\",\"open\":\"09:30\",\"close\":\"16:00\"}]",
            "[{\"date\":\"2026-02-01\",\"open\":\"09:30\",\"close\":\"16:00\"}]",
            "[{\"date\":\"2026-02-02\",\"open\":\"25:00\",\"close\":\"16:00\"}]",
            "[{\"date\":\"2026-02-02\",\"open\":\"16:00\",\"close\":\"09:30\"}]",
            "[{\"date\":\"2026-02-02\",\"open\":\"09:30\",\"close\":\"16:00\"},{\"date\":\"2026-02-02\",\"open\":\"09:30\",\"close\":\"16:00\"}]"
    })
    void 잘못된_응답은_저장하지_않는다(String body) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://paper-api.alpaca.markets");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaProperties properties = new AlpacaProperties("https://paper-api.alpaca.markets", "test-key", "test-secret", "https://data.test");
        MarketHolidayStorePort holidayStorePort = mock(MarketHolidayStorePort.class);
        AlpacaCalendarAdapter adapter = new AlpacaCalendarAdapter(builder.build(), properties, holidayStorePort);

        server.expect(requestTo("https://paper-api.alpaca.markets/v2/calendar?start=2026-02-01&end=2026-02-28"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.refreshMonth(2026, 2)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(holidayStorePort);
        server.verify();
    }
}
