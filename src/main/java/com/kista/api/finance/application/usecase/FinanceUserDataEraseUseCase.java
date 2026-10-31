package com.kista.api.finance.application.usecase;

import java.util.UUID;

// 회원 탈퇴 cascade — finance 소유 재무 기록·그룹 멤버십 정리 (FinanceUserCascadeListener 전용 진입점)
public interface FinanceUserDataEraseUseCase {
    void eraseUserData(UUID userId); // 호출자 트랜잭션 합류 — 리스너가 REQUIRES_NEW로 경계를 연다
}
