package com.kista.api.user.adapter.out.persistence.user;

import com.kista.api.web.RootDataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.shared.kernel.UserStatus;

// countGroupByStatus — 상태별 사용자 수 단일 GROUP BY 집계 검증 (soft delete 제외 포함)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class UserJpaRepositoryCountTest extends RootDataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserJpaRepository userJpaRepository;

    private void insertUser(UserStatus status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, now(), now(), ?)",
                id, "kakao_" + id, status.name(), "USER", deleted ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
    }

    // 상태별 현재 집계값 — 공유 kistadb_test에 다른 실행(동시 gradle·중단된 IT 잔재)이 커밋한 행이 있어도 깨지지 않도록 기준값 대비 증가분만 단언한다
    private long countOf(UserStatus status) {
        return userJpaRepository.countGroupByStatus().stream()
                .filter(p -> p.getStatus() == status)
                .mapToLong(UserJpaRepository.StatusCountProjection::getCount)
                .sum();
    }

    @Test
    void countGroupByStatus_상태별_사용자수를_집계하고_soft_delete는_제외한다() {
        long pendingBefore = countOf(UserStatus.PENDING);
        long activeBefore = countOf(UserStatus.ACTIVE);
        long rejectedBefore = countOf(UserStatus.REJECTED);

        insertUser(UserStatus.PENDING, false);
        insertUser(UserStatus.ACTIVE, false);
        insertUser(UserStatus.ACTIVE, false);
        insertUser(UserStatus.REJECTED, false);
        insertUser(UserStatus.ACTIVE, true); // soft-deleted — 집계 제외 대상

        assertThat(countOf(UserStatus.PENDING) - pendingBefore).isEqualTo(1L);
        assertThat(countOf(UserStatus.ACTIVE) - activeBefore).isEqualTo(2L); // soft-deleted 1건 제외
        assertThat(countOf(UserStatus.REJECTED) - rejectedBefore).isEqualTo(1L);
    }
}
