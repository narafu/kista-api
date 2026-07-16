package com.kista.application.service.market;

import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.out.CnnFearGreedPort;
import com.kista.domain.port.out.CryptoFearGreedPort;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// FearGreedService 순수 Mockito 단위 테스트 — CRYPTO/CNN 독립 실패 격리 검증
@Execution(ExecutionMode.SAME_THREAD)
class FearGreedServiceTest {

    @Mock
    private CryptoFearGreedPort cryptoFearGreedPort;

    @Mock
    private CnnFearGreedPort cnnFearGreedPort;

    @Mock
    private FearGreedSnapshotPort fearGreedSnapshotPort;

    @Mock
    private NotifyPort notifyPort;

    private FearGreedService fearGreedService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        fearGreedService = new FearGreedService(cryptoFearGreedPort, cnnFearGreedPort, fearGreedSnapshotPort, notifyPort);
    }

    @Test
    @DisplayName("CRYPTO·CNN 모두 fetch 성공 시 각각 스냅샷 저장")
    void fetchAndSave_bothSucceed_savesBothSnapshots() {
        // given: CRYPTO·CNN 모두 정상 fetch
        Instant snapshotDate = Instant.parse("2026-07-16T00:00:00Z");
        when(cryptoFearGreedPort.fetch())
                .thenReturn(new CryptoFearGreedPort.CryptoFearGreedData(30, FearGreedRating.FEAR));
        when(cnnFearGreedPort.fetch())
                .thenReturn(new CnnFearGreedPort.CnnFearGreedData(70, FearGreedRating.GREED));

        // when
        fearGreedService.fetchAndSave(snapshotDate);

        // then: 2건 저장, source 필드가 각각 CRYPTO/CNN인지 검증
        ArgumentCaptor<FearGreedSnapshot> captor = ArgumentCaptor.forClass(FearGreedSnapshot.class);
        verify(fearGreedSnapshotPort, times(2)).save(captor.capture());
        List<FearGreedSnapshot> saved = captor.getAllValues();

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).source()).isEqualTo("CRYPTO");
        assertThat(saved.get(0).value()).isEqualTo(30);
        assertThat(saved.get(0).rating()).isEqualTo(FearGreedRating.FEAR);
        assertThat(saved.get(1).source()).isEqualTo("CNN");
        assertThat(saved.get(1).value()).isEqualTo(70);
        assertThat(saved.get(1).rating()).isEqualTo(FearGreedRating.GREED);
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    @DisplayName("CRYPTO fetch 실패 시 notifyError 호출 + CNN은 정상 저장 (실패 격리)")
    void fetchAndSave_cryptoFails_notifiesErrorAndStillSavesCnn() {
        // given: CRYPTO fetch가 예외 발생, CNN은 정상
        Instant snapshotDate = Instant.parse("2026-07-16T00:00:00Z");
        RuntimeException cryptoFailure = new RuntimeException("crypto api down");
        when(cryptoFearGreedPort.fetch()).thenThrow(cryptoFailure);
        when(cnnFearGreedPort.fetch())
                .thenReturn(new CnnFearGreedPort.CnnFearGreedData(50, FearGreedRating.NEUTRAL));

        // when
        fearGreedService.fetchAndSave(snapshotDate);

        // then: CRYPTO 실패는 notifyError로 격리되고 CNN 저장까지 도달
        verify(notifyPort, times(1)).notifyError(cryptoFailure);

        ArgumentCaptor<FearGreedSnapshot> captor = ArgumentCaptor.forClass(FearGreedSnapshot.class);
        verify(fearGreedSnapshotPort, times(1)).save(captor.capture());
        FearGreedSnapshot saved = captor.getValue();
        assertThat(saved.source()).isEqualTo("CNN");
        assertThat(saved.value()).isEqualTo(50);
        assertThat(saved.rating()).isEqualTo(FearGreedRating.NEUTRAL);
    }

    @Test
    @DisplayName("CNN fetch 실패 시 notifyError 호출 + CRYPTO는 정상 저장 (대칭 케이스)")
    void fetchAndSave_cnnFails_notifiesErrorAndStillSavesCrypto() {
        // given: CNN fetch가 예외 발생, CRYPTO는 정상
        Instant snapshotDate = Instant.parse("2026-07-16T00:00:00Z");
        when(cryptoFearGreedPort.fetch())
                .thenReturn(new CryptoFearGreedPort.CryptoFearGreedData(80, FearGreedRating.EXTREME_GREED));
        RuntimeException cnnFailure = new RuntimeException("cnn api down");
        when(cnnFearGreedPort.fetch()).thenThrow(cnnFailure);

        // when
        fearGreedService.fetchAndSave(snapshotDate);

        // then: CNN 실패는 notifyError로 격리되고 CRYPTO 저장은 그대로 유지
        verify(notifyPort, times(1)).notifyError(cnnFailure);

        ArgumentCaptor<FearGreedSnapshot> captor = ArgumentCaptor.forClass(FearGreedSnapshot.class);
        verify(fearGreedSnapshotPort, times(1)).save(captor.capture());
        FearGreedSnapshot saved = captor.getValue();
        assertThat(saved.source()).isEqualTo("CRYPTO");
        assertThat(saved.value()).isEqualTo(80);
        assertThat(saved.rating()).isEqualTo(FearGreedRating.EXTREME_GREED);
    }
}
