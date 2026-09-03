package com.kista.user.adapter.out.persistence.user;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.UserStatus;

// countGroupByStatus — 상태별 사용자 수 단일 GROUP BY 집계 검증 (soft delete 제외 포함)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class UserJpaRepositoryCountTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserJpaRepository userJpaRepository;

    private void insertUser(UserStatus status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, now(), now(), ?)",
                id, "kakao_" + id, status.name(), "USER", deleted ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
    }

    @Test
    void countGroupByStatus_상태별_사용자수를_집계하고_soft_delete는_제외한다() {
        insertUser(UserStatus.PENDING, false);
        insertUser(UserStatus.ACTIVE, false);
        insertUser(UserStatus.ACTIVE, false);
        insertUser(UserStatus.REJECTED, false);
        insertUser(UserStatus.ACTIVE, true); // soft-deleted — 집계 제외 대상

        List<UserJpaRepository.StatusCountProjection> result = userJpaRepository.countGroupByStatus();

        long pending = result.stream().filter(p -> p.getStatus() == UserStatus.PENDING).mapToLong(UserJpaRepository.StatusCountProjection::getCount).sum();
        long active = result.stream().filter(p -> p.getStatus() == UserStatus.ACTIVE).mapToLong(UserJpaRepository.StatusCountProjection::getCount).sum();
        long rejected = result.stream().filter(p -> p.getStatus() == UserStatus.REJECTED).mapToLong(UserJpaRepository.StatusCountProjection::getCount).sum();

        assertThat(pending).isEqualTo(1L);
        assertThat(active).isEqualTo(2L); // soft-deleted 1건 제외
        assertThat(rejected).isEqualTo(1L);
    }
}
