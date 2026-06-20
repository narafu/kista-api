package com.kista.adapter.out.persistence.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

// package-private — 선언 패키지 외부 직접 접근 금지 (constraints.md 참고)
interface UserNotificationPrefJpaRepository extends JpaRepository<UserNotificationPrefJpaEntity, UserNotificationPrefId> {
    // userId 기준으로 해당 사용자의 모든 알림 선호도 조회
    List<UserNotificationPrefJpaEntity> findByUserId(UUID userId);
}
