package com.kista.api.market.application.service;

import com.kista.api.market.application.event.FearGreedFetchFailedEvent;
import com.kista.api.market.domain.model.FearGreedRating;
import com.kista.api.market.domain.model.FearGreedSnapshot;
import com.kista.api.market.application.port.output.CnnFearGreedPort;
import com.kista.api.market.application.port.output.FearGreedSnapshotPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// FearGreedService 순수 Mockito 단위 테스트 — CNN 수집·저장과 실패 이벤트 검증
@Execution(ExecutionMode.SAME_THREAD)
class FearGreedServiceTest {

    @Mock
    private CnnFearGreedPort cnnFearGreedPort;

    @Mock
    private FearGreedSnapshotPort fearGreedSnapshotPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private FearGreedService fearGreedService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        fearGreedService = new FearGreedService(cnnFearGreedPort, fearGreedSnapshotPort, eventPublisher);
    }

    @Test
    @DisplayName("CNN fetch 성공 시 CNN 스냅샷만 저장")
    void fetchAndSave_cnnSucceeds_savesCnnSnapshot() {
        // given: CNN 정상 fetch
        Instant snapshotDate = Instant.parse("2026-07-16T00:00:00Z");
        when(cnnFearGreedPort.fetch())
                .thenReturn(new CnnFearGreedPort.CnnFearGreedData(70, FearGreedRating.GREED));

        // when
        fearGreedService.fetchAndSave(snapshotDate);

        // then: CNN 1건 저장, 실패 이벤트 없음
        ArgumentCaptor<FearGreedSnapshot> captor = ArgumentCaptor.forClass(FearGreedSnapshot.class);
        verify(fearGreedSnapshotPort, times(1)).save(captor.capture());
        FearGreedSnapshot saved = captor.getValue();
        assertThat(saved.source()).isEqualTo("CNN");
        assertThat(saved.snapshotDate()).isEqualTo(snapshotDate);
        assertThat(saved.value()).isEqualTo(70);
        assertThat(saved.rating()).isEqualTo(FearGreedRating.GREED);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("CNN fetch 실패 시 저장 없이 실패 이벤트 발행")
    void fetchAndSave_cnnFails_publishesFailedEvent() {
        // given: CNN fetch가 예외 발생
        RuntimeException cnnFailure = new RuntimeException("cnn api down");
        when(cnnFearGreedPort.fetch()).thenThrow(cnnFailure);

        // when
        fearGreedService.fetchAndSave(Instant.parse("2026-07-16T00:00:00Z"));

        // then: 예외는 이벤트로 격리, 저장 없음
        verify(eventPublisher, times(1)).publishEvent(new FearGreedFetchFailedEvent(cnnFailure.getMessage()));
        verify(fearGreedSnapshotPort, never()).save(any());
    }
}
