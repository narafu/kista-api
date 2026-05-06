package com.kista.domain.model;

public enum UserStatus {
    PENDING,  // 관리자 승인 대기 중
    ACTIVE,   // 승인 완료, 서비스 이용 가능
    REJECTED  // 거절됨 (재신청 가능)
}
