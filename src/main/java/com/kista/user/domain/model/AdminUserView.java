package com.kista.user.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

// 관리자 화면 전용 read-model — User record와 분리하여 가입 일시를 안전하게 노출
public record AdminUserView(
        UUID id,
        String nickname,
        UserStatus status,
        UserRole role,
        Instant createdAt     // 가입 일시 (BaseAuditEntity에서 직접 읽어옴)
) {}
