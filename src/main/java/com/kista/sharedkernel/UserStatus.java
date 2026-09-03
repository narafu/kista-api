package com.kista.sharedkernel;

// 계정 상태 — User 도메인 nested enum에서 sharedkernel로 이관.
// 상수명 byte-identical 유지 필수 — UserEntity.status @Enumerated(STRING) DB 컬럼과 직결.
public enum UserStatus {
    PENDING,  // 관리자 승인 대기 중
    ACTIVE,   // 승인 완료, 서비스 이용 가능
    REJECTED  // 거절됨 (재신청 가능)
}
