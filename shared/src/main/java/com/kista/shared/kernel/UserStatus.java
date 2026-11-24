package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 계정 상태 — User 도메인 nested enum에서 shared.kernel로 이관.
// 상수명 byte-identical 유지 필수 — UserEntity.status @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum UserStatus {
    PENDING("승인 대기"),  // 관리자 승인 대기 중
    ACTIVE("승인 완료"),   // 승인 완료, 서비스 이용 가능
    REJECTED("반려");  // 거절됨 (재신청 가능)

    private final String label; // 한국어 표시 이름 (메타 번들)
}
