package com.kista.adapter.out.persistence.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

// package-private — 선언 패키지 외부 직접 접근 금지 (constraints.md 참고)
interface UserSettingsJpaRepository extends JpaRepository<UserSettingsJpaEntity, UUID> {}
