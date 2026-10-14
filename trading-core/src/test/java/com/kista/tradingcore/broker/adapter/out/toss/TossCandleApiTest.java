package com.kista.tradingcore.broker.adapter.out.toss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("TossCandleApi 단위 테스트")
class TossCandleApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossCandleApi tossCandleApi;

    @BeforeEach
    void setUp() {
        tossCandleApi = new TossCandleApi(tossHttpClient);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("월 경계를 넘는 범위는 실제 경과일수(ChronoUnit) 기준으로 count를 계산한다")
    void getCandles_crossMonthRange_computesCountByActualElapsedDays() {
        // Period.getDays()로 계산하면 8(월 성분 제외)로 잘못 산출돼 count가 과소 요청되던 버그 케이스
        LocalDate from = LocalDate.of(2026, 1, 20);
        LocalDate to = LocalDate.of(2026, 2, 28); // 실제 경과 39일 + 1 = 40일
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(new TossResult<>(new TossCandleApi.CandlesResult(java.util.List.of())));

        tossCandleApi.getCandles("TQQQ", "1d", from, to);

        ArgumentCaptor<MultiValueMap<String, String>> paramsCaptor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient).getCommon(eq("/api/v1/candles"), paramsCaptor.capture(), any(ParameterizedTypeReference.class));
        // 정상 계산: calendarDays=40 -> count = 40*3/2+5 = 65 (버그 시 calendarDays=9 -> count=18)
        assertThat(paramsCaptor.getValue().getFirst("count")).isEqualTo("65");
    }

    private static TossResult<TossCandleApi.CandlesResult> page(String... dates) {
        return new TossResult<>(new TossCandleApi.CandlesResult(java.util.Arrays.stream(dates)
                .map(d -> new TossCandleApi.CandleItem(d + "T00:00:00Z", "1", "1", "1", "1", "1")).toList()));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("일봉 이전 조회는 US 세션일 이후 봉을 제외하고 before를 세션일 00:00 UTC로 보낸다")
    void getDailyCandlesBefore_excludesSessionAndLater() {
        // 1페이지 응답: 10-14(진행 중 세션), 10-13, 10-12 — 최신순, 2페이지는 빈 응답으로 종료
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(page("2026-10-14", "2026-10-13", "2026-10-12"), page());

        var result = tossCandleApi.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 5);

        assertThat(result).extracting(c -> c.date()).containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13));
        ArgumentCaptor<MultiValueMap<String, String>> paramsCaptor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient, times(2)).getCommon(eq("/api/v1/candles"), paramsCaptor.capture(), any(ParameterizedTypeReference.class));
        assertThat(paramsCaptor.getAllValues().getFirst().getFirst("before")).isEqualTo("2026-10-14T00:00Z");
        assertThat(paramsCaptor.getAllValues().getFirst().getFirst("count")).isEqualTo("5");
        assertThat(paramsCaptor.getAllValues().getFirst().getFirst("adjusted")).isEqualTo("true");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("일봉 이전 조회는 200봉 단위로 페이징하고 경계 중복을 제거해 마지막 count개를 오름차순으로 준다")
    void paging_dedups_boundary_and_returns_last_count() {
        // 1페이지: 10-14(진행 중) 10-13 10-12, 2페이지: 10-12(경계 중복) 10-09 10-08, 3페이지: 빈 응답
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(page("2026-10-14", "2026-10-13", "2026-10-12"),
                        page("2026-10-12", "2026-10-09", "2026-10-08"),
                        page());

        var result = tossCandleApi.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 4);

        // 1페이지 필터 후 {10-12, 10-13}, 2페이지(before=10-12)는 10-12 제외 {10-08, 10-09} → 4개로 count 충족, 3페이지 미요청
        assertThat(result).extracting(c -> c.date()).containsExactly(
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13));
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient, times(2)).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getAllValues().get(1).getFirst("before")).isEqualTo("2026-10-12T00:00Z");
        assertThat(captor.getAllValues()).allSatisfy(p -> assertThat(p.getFirst("adjusted")).isEqualTo("true"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("250봉 요청은 200봉 + 50봉 두 페이지로 채우고 오름차순으로 준다")
    void paging_fills_250_candles_over_two_pages() {
        LocalDate latest = LocalDate.of(2026, 10, 13);
        // 최신 200봉(오름차순) → 그 이전 50봉(오름차순)
        String[] newer = java.util.stream.IntStream.rangeClosed(0, 199).map(i -> 199 - i)
                .mapToObj(i -> latest.minusDays(i).toString()).toArray(String[]::new);
        String[] older = java.util.stream.IntStream.rangeClosed(200, 249).map(i -> 449 - i)
                .mapToObj(i -> latest.minusDays(i).toString()).toArray(String[]::new);
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class))).thenReturn(page(newer), page(older));

        var result = tossCandleApi.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 250);

        assertThat(result).hasSize(250);
        assertThat(result.getFirst().date()).isEqualTo(latest.minusDays(249));
        assertThat(result.getLast().date()).isEqualTo(latest);
        assertThat(result).extracting(c -> c.date()).isSorted();
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient, times(2)).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getAllValues().get(0).getFirst("count")).isEqualTo("200");
        assertThat(captor.getAllValues().get(1).getFirst("count")).isEqualTo("50");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("count가 200을 넘으면 요청당 count는 200 이하다")
    void page_size_is_clamped_to_200() {
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class))).thenReturn(page());
        tossCandleApi.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 250);
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().getFirst("count")).isEqualTo("200");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("다른 캔들 조회는 adjusted를 보내지 않는다")
    void other_queries_do_not_send_adjusted() {
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class))).thenReturn(page());
        tossCandleApi.getLatestCandles("UPRO", "1d", 5);
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().containsKey("adjusted")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("기간 조회는 200봉을 넘으면 가장 오래된 봉 이전으로 이어서 페이징하고 from 미만을 버린다")
    void getCandles_longRange_pagesBeyond200() {
        // 1페이지: 200봉(10-14부터 역순 날짜) — 2페이지: from 이전 봉 1개 포함
        LocalDate to = LocalDate.of(2026, 10, 14);
        String[] firstPage = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> to.minusDays(i).toString()).toArray(String[]::new);
        LocalDate oldestFirst = to.minusDays(199);
        LocalDate from = oldestFirst.minusDays(2);
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(page(firstPage),
                        page(oldestFirst.minusDays(1).toString(), from.toString(), from.minusDays(1).toString()));

        var result = tossCandleApi.getCandles("SOXL", "1d", from, to);

        assertThat(result).hasSize(202);
        assertThat(result.getFirst().date()).isEqualTo(from);
        assertThat(result.getLast().date()).isEqualTo(to);
        ArgumentCaptor<MultiValueMap<String, String>> paramsCaptor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient, times(2)).getCommon(eq("/api/v1/candles"), paramsCaptor.capture(), any(ParameterizedTypeReference.class));
        assertThat(paramsCaptor.getAllValues().get(0).getFirst("count")).isEqualTo("200");
        assertThat(paramsCaptor.getAllValues().get(1).getFirst("before")).isEqualTo(oldestFirst + "T00:00Z");
        assertThat(paramsCaptor.getAllValues().get(1).getFirst("adjusted")).isNull();
    }
}
