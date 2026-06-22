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

import java.time.LocalDate;
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
        var snap = new FearGreedSnapshot(null, "CNN", LocalDate.of(2026, 6, 22), 72, FearGreedRating.GREED, null);
        when(port.findBySourceSince(eq("CNN"), any(LocalDate.class)))
                .thenReturn(List.of(snap));

        List<FearGreedSnapshot> result = service.getRecent("CNN", 90);

        assertThat(result).containsExactly(snap);
        ArgumentCaptor<LocalDate> since = ArgumentCaptor.forClass(LocalDate.class);
        verify(port).findBySourceSince(eq("CNN"), since.capture());
        // 90일 전(포함) 이하의 날짜로 조회해야 함 — LocalDate.now().minusDays(90) 이하
        assertThat(since.getValue()).isBeforeOrEqualTo(LocalDate.now().minusDays(89));
    }
}
