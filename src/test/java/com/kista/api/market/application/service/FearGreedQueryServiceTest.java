package com.kista.api.market.application.service;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // clock.instant() stub은 CNN 경로 일부 테스트에서 쓰이지 않는다
class FearGreedQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z"); // KST 2026-10-20 12:00

    @Mock FearGreedSnapshotPort port;
    @Mock Clock clock;
    FearGreedQueryService service;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        service = new FearGreedQueryService(port, clock);
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
        assertThatThrownBy(() -> service.getRecent("CNN", 366)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(port);
    }
}
