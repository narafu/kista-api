package com.kista.api.market.application.service;

import com.kista.api.market.application.port.output.CryptoFearGreedPort;
import com.kista.api.market.domain.model.FearGreedRating;
import com.kista.api.market.domain.model.FearGreedSnapshot;
import com.kista.api.market.application.port.output.FearGreedSnapshotPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // clock.instant() stub은 CNN 경로 일부 테스트에서 쓰이지 않는다
class FearGreedQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z"); // KST 2026-10-20 12:00

    @Mock FearGreedSnapshotPort port;
    @Mock CryptoFearGreedPort cryptoPort;
    @Mock Clock clock;
    FearGreedQueryService service;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        service = new FearGreedQueryService(port, cryptoPort, clock);
    }

    private static FearGreedSnapshot crypto(String utcMidnight, int value) {
        return FearGreedSnapshot.of("CRYPTO", Instant.parse(utcMidnight), value, FearGreedRating.NEUTRAL);
    }

    @Test
    void getRecent_passes_source_and_since_cutoff() {
        var snap = new FearGreedSnapshot(null, "CNN", Instant.parse("2026-06-22T00:00:00Z"), 72, FearGreedRating.GREED, null);
        when(port.findBySourceSince(eq("CNN"), any(Instant.class)))
                .thenReturn(List.of(snap));

        List<FearGreedSnapshot> result = service.getRecent("CNN", 90);

        assertThat(result).containsExactly(snap);
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(port).findBySourceSince(eq("CNN"), since.capture());
        // 90일 전 KST 자정으로 조회해야 함
        Instant expected = LocalDate.of(2026, 10, 20).minusDays(90)
                .atStartOfDay(ZoneId.of("Asia/Seoul"))
                .toInstant();
        assertThat(since.getValue()).isEqualTo(expected);
        verifyNoInteractions(cryptoPort);
    }

    @Test
    void getRecent_returns_latest_snapshot_per_kst_day() {
        var day1Morning = new FearGreedSnapshot(null, "CNN", Instant.parse("2026-06-21T00:00:00Z"), 40, FearGreedRating.FEAR, null);
        var day1Evening = new FearGreedSnapshot(null, "CNN", Instant.parse("2026-06-21T09:00:00Z"), 55, FearGreedRating.NEUTRAL, null);
        var day2Morning = new FearGreedSnapshot(null, "CNN", Instant.parse("2026-06-22T00:00:00Z"), 60, FearGreedRating.GREED, null);
        var day2Evening = new FearGreedSnapshot(null, "CNN", Instant.parse("2026-06-22T09:00:00Z"), 72, FearGreedRating.GREED, null);
        when(port.findBySourceSince(eq("CNN"), any(Instant.class)))
                .thenReturn(List.of(day1Morning, day1Evening, day2Morning, day2Evening));

        List<FearGreedSnapshot> result = service.getRecent("CNN", 90);

        assertThat(result).containsExactly(day1Evening, day2Evening);
    }

    @Test
    void getRecent_rejects_days_out_of_range() {
        assertThatThrownBy(() -> service.getRecent("CNN", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getRecent("CRYPTO", 366)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(port, cryptoPort);
    }

    @Test
    void crypto_reads_history_from_api_not_db_and_cuts_by_kst_since() {
        // since = 2026-10-18 KST 00:00 = 2026-10-17T15:00Z → 10-17 UTC 자정 항목은 제외
        var oct17 = crypto("2026-10-17T00:00:00Z", 30);
        var oct18 = crypto("2026-10-18T00:00:00Z", 40);
        var oct19 = crypto("2026-10-19T00:00:00Z", 50);
        var oct20 = crypto("2026-10-20T00:00:00Z", 60);
        when(cryptoPort.fetchHistory(FearGreedQueryService.MAX_DAYS + 1)).thenReturn(List.of(oct17, oct18, oct19, oct20));

        List<FearGreedSnapshot> result = service.getRecent("CRYPTO", 2);

        assertThat(result).containsExactly(oct18, oct19, oct20);
        verifyNoInteractions(port);
    }

    @Test
    void crypto_history_is_cached_across_days_options() {
        when(cryptoPort.fetchHistory(anyInt())).thenReturn(List.of(crypto("2026-10-20T00:00:00Z", 60)));

        service.getRecent("CRYPTO", 30);
        service.getRecent("CRYPTO", 365);

        verify(cryptoPort, times(1)).fetchHistory(anyInt());
    }

    @Test
    void crypto_failure_returns_empty_and_is_cached_briefly() {
        var oct20 = crypto("2026-10-20T00:00:00Z", 60);
        when(cryptoPort.fetchHistory(anyInt()))
                .thenThrow(new IllegalStateException("alternative.me down"))
                .thenReturn(List.of(oct20));

        // 실패 → 빈 결과, 실패 TTL 안에서는 외부 재호출 없음
        assertThat(service.getRecent("CRYPTO", 30)).isEmpty();
        assertThat(service.getRecent("CRYPTO", 30)).isEmpty();
        verify(cryptoPort, times(1)).fetchHistory(anyInt());

        // 실패 TTL 경과 후 재조회
        when(clock.instant()).thenReturn(NOW.plus(FearGreedQueryService.CRYPTO_FAILURE_TTL));
        assertThat(service.getRecent("CRYPTO", 30)).containsExactly(oct20);
        verify(cryptoPort, times(2)).fetchHistory(anyInt());
    }
}
