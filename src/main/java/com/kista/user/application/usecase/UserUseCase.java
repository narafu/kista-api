package com.kista.user.application.usecase;

import com.kista.sharedkernel.UserRole;
import com.kista.user.domain.model.User;

import java.util.UUID;

public interface UserUseCase {
    // --- 조회 ---
    User getById(UUID id);
    java.util.Optional<UUID> findUserIdByTelegramChatId(String chatId); // 텔레그램 봇 명령 발신자 userId 조회

    // --- 카카오 로그인 ---
    User login(String code, String redirectUri);

    // --- 회원가입 ---
    User register(String kakaoId, String nickname, UUID userId, String email);

    // --- 승인 ---
    void approve(UUID userId);
    void reject(UUID userId, String reason); // reason은 optional (blank -> null 정규화는 구현체 책임)
    void reapply(UUID userId);

    // --- 역할 변경 ---
    void changeRole(UUID userId, UserRole role); // 역할 저장 + 기존 AT 무효화 (자기 강등·마지막 ADMIN 검증은 호출자 정책)

    // --- 탈퇴 ---
    void deleteMe(UUID userId);
}
