package com.kista.application.service.market;

import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FearGreedQueryServiceTest {

    @Mock FearGreedSnapshotPort port;
    @InjectMocks FearGreedQueryService service;

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
        Instant expected = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(90)
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
}
