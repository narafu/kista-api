package com.kista.user.adapter.out.persistence.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByKakaoId(String kakaoId);
    Optional<UserEntity> findByTelegramChatId(String chatId); // 텔레그램 봇 명령 발신자 식별용
    List<UserEntity> findAllByOrderByCreatedAtDesc(); // 관리자 전체 조회 — 최신순
    List<UserEntity> findAllByStatus(UserStatus status); // 상태별 조회 (관리자용)
    List<UserEntity> findAllByStatusOrderByCreatedAtDesc(UserStatus status); // 상태별 최신순
    long countByRole(UserRole role); // 역할별 사용자 수 (Spring Data JPA 자동 파생)

    // 상태별 사용자 수 단일 GROUP BY 집계 (관리자 통계용) — countAll+countByStatus×3 직렬 호출 대체
    @Query("SELECT u.status AS status, COUNT(u) AS count FROM UserEntity u GROUP BY u.status")
    List<StatusCountProjection> countGroupByStatus();

    // GROUP BY 프로젝션 — 결과에 없는 상태는 호출측에서 getOrDefault(0)로 처리
    interface StatusCountProjection {
        UserStatus getStatus();
        long getCount();
    }

    @Modifying
    @Query("UPDATE UserEntity u SET u.deletedAt = :now WHERE u.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);
}
