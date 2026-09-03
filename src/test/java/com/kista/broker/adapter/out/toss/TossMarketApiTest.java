package com.kista.broker.adapter.out.toss;

import com.kista.broker.domain.model.toss.TossMarketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("TossMarketApi 단위 테스트")
class TossMarketApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossMarketApi tossMarketApi;

    @BeforeEach
    void setUp() {
        tossMarketApi = new TossMarketApi(tossHttpClient);
    }

    // 개장일 응답 래퍼 헬퍼 — regularMarket이 있으면 개장일
    private static TossResult<TossMarketApi.MarketCalendarResponse> openDayWrapper(String date) {
        TossMarketApi.SessionWindow regular = new TossMarketApi.SessionWindow(
                date + "T13:30:00Z", date + "T20:00:00Z");
        TossMarketApi.MarketDay today = new TossMarketApi.MarketDay(date, null, regular, null);
        return new TossResult<>(new TossMarketApi.MarketCalendarResponse(today));
    }

    @Test
    @DisplayName("범위가 30일 초과면 IllegalArgumentException")
    void getMarketCalendar_rangeExceedsMax_throws() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = from.plusDays(30); // 31일 범위

        assertThatThrownBy(() -> tossMarketApi.getMarketCalendar(from, to))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("개장일 응답을 정상 파싱해 regularMarket을 채운다")
    void getMarketCalendar_parsesOpenDay() {
        LocalDate date = LocalDate.of(2026, 7, 29); // 과거 확정 날짜
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(openDayWrapper(date.toString()));

        List<TossMarketSession> result = tossMarketApi.getMarketCalendar(date, date);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isOpen()).isTrue();
        assertThat(result.get(0).date()).isEqualTo(date);
    }

    @Test
    @DisplayName("과거 날짜 재조회는 캐시 히트로 HTTP 1회만 호출한다")
    void getMarketCalendar_pastDate_cachedAcrossCalls() {
        // 90일 정리 대상에 걸리지 않도록 실제 시각 기준 최근 과거(어제)로 계산
        LocalDate date = LocalDate.now(java.time.ZoneId.of("America/New_York")).minusDays(1);
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(openDayWrapper(date.toString()));

        tossMarketApi.getMarketCalendar(date, date);
        tossMarketApi.getMarketCalendar(date, date);

        verify(tossHttpClient, times(1)).getCommon(any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("응답 없음(today=null)이면 휴장 폴백을 반환하고 캐싱하지 않는다")
    void getMarketCalendar_emptyResponse_fallsBackAndNotCached() {
        LocalDate date = LocalDate.of(2026, 1, 2); // 과거 확정 날짜
        TossMarketApi.MarketCalendarResponse emptyResponse = new TossMarketApi.MarketCalendarResponse(null);
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(new TossResult<>(emptyResponse));

        List<TossMarketSession> first = tossMarketApi.getMarketCalendar(date, date);
        List<TossMarketSession> second = tossMarketApi.getMarketCalendar(date, date);

        assertThat(first.get(0).isOpen()).isFalse();
        assertThat(second.get(0).isOpen()).isFalse();
        // 캐싱되지 않았으므로 매 호출마다 재조회
        verify(tossHttpClient, times(2)).getCommon(any(), any(), any(ParameterizedTypeReference.class));
    }
}
