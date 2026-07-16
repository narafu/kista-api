package com.kista.adapter.out.persistence.feargreed;

import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(FearGreedSnapshotPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class FearGreedSnapshotPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired FearGreedSnapshotPersistenceAdapter snapshotAdapter;

    @Test
    void save_thenFindBySourceSince_returnsSavedSnapshot() {
        Instant snapshotDate = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        FearGreedSnapshot snapshot = FearGreedSnapshot.of("CNN", snapshotDate, 42, FearGreedRating.FEAR);

        snapshotAdapter.save(snapshot);

        List<FearGreedSnapshot> found = snapshotAdapter.findBySourceSince("CNN", snapshotDate.minus(1, ChronoUnit.HOURS));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).source()).isEqualTo("CNN");
        assertThat(found.get(0).value()).isEqualTo(42);
        assertThat(found.get(0).rating()).isEqualTo(FearGreedRating.FEAR);
        assertThat(found.get(0).id()).isNotNull();
    }

    @Test
    void findBySourceSince_multipleSnapshots_returnsAscendingWithLatestLast() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(3, ChronoUnit.HOURS);

        snapshotAdapter.save(FearGreedSnapshot.of("CRYPTO", base, 10, FearGreedRating.EXTREME_FEAR));
        snapshotAdapter.save(FearGreedSnapshot.of("CRYPTO", base.plus(1, ChronoUnit.HOURS), 50, FearGreedRating.NEUTRAL));
        snapshotAdapter.save(FearGreedSnapshot.of("CRYPTO", base.plus(2, ChronoUnit.HOURS), 90, FearGreedRating.EXTREME_GREED));

        List<FearGreedSnapshot> found = snapshotAdapter.findBySourceSince("CRYPTO", base);

        assertThat(found).hasSize(3);
        assertThat(found).extracting(FearGreedSnapshot::value).containsExactly(10, 50, 90); // 시각 오름차순
        FearGreedSnapshot latest = found.get(found.size() - 1);
        assertThat(latest.value()).isEqualTo(90);
        assertThat(latest.rating()).isEqualTo(FearGreedRating.EXTREME_GREED);
    }

    @Test
    void findBySourceSince_separatesBySource() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        snapshotAdapter.save(FearGreedSnapshot.of("CRYPTO", now, 20, FearGreedRating.FEAR));
        snapshotAdapter.save(FearGreedSnapshot.of("CNN", now, 80, FearGreedRating.GREED));

        List<FearGreedSnapshot> cryptoOnly = snapshotAdapter.findBySourceSince("CRYPTO", now.minus(1, ChronoUnit.HOURS));
        List<FearGreedSnapshot> cnnOnly = snapshotAdapter.findBySourceSince("CNN", now.minus(1, ChronoUnit.HOURS));

        assertThat(cryptoOnly).hasSize(1);
        assertThat(cryptoOnly.get(0).source()).isEqualTo("CRYPTO");
        assertThat(cryptoOnly.get(0).value()).isEqualTo(20);
        assertThat(cnnOnly).hasSize(1);
        assertThat(cnnOnly.get(0).source()).isEqualTo("CNN");
        assertThat(cnnOnly.get(0).value()).isEqualTo(80);
    }

    @Test
    void findBySourceSince_beforeSinceBoundary_excludesOlderSnapshot() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant older = now.minus(2, ChronoUnit.HOURS);

        snapshotAdapter.save(FearGreedSnapshot.of("CNN", older, 30, FearGreedRating.FEAR));
        snapshotAdapter.save(FearGreedSnapshot.of("CNN", now, 60, FearGreedRating.NEUTRAL));

        List<FearGreedSnapshot> found = snapshotAdapter.findBySourceSince("CNN", now.minus(1, ChronoUnit.HOURS));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).value()).isEqualTo(60);
    }
}
