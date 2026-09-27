package com.kista.broker.adapter.out.toss;

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
}
