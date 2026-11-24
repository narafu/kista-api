package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 사용자 권한 — User 도메인 nested enum에서 shared.kernel로 이관(constraints.md "User/Account/Strategy 공유 enum").
// 상수명 byte-identical 유지 필수 — UserEntity.role @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum UserRole {
    USER("일반 사용자"),
    ADMIN("관리자");

    private final String label; // 한국어 표시 이름 (메타 번들)
}
