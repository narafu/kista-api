package com.kista.sharedkernel;

// 사용자 권한 — User 도메인 nested enum에서 sharedkernel로 이관(constraints.md "nested enum 정책 개정").
// 상수명 byte-identical 유지 필수 — UserEntity.role @Enumerated(STRING) DB 컬럼과 직결.
public enum UserRole { USER, ADMIN }
