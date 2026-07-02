package com.kista.domain.port.in;

import com.kista.domain.model.user.User;

import java.util.UUID;

public interface UserUseCase {
    // --- 조회 ---
    User getById(UUID id);
    User getByKakaoId(String kakaoId);
    java.util.Optional<UUID> findUserIdByTelegramChatId(String chatId); // 텔레그램 봇 명령 발신자 userId 조회

    // --- 카카오 로그인 ---
    User login(String code, String redirectUri);

    // --- 회원가입 ---
    User register(String kakaoId, String nickname, UUID userId);

    // --- 승인 ---
    void approve(UUID userId);
    void reject(UUID userId);
    void reapply(UUID userId);

    // --- 탈퇴 ---
    void deleteMe(UUID userId);
}
