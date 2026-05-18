package com.kista.domain.port.out;

// 텔레그램 봇 토큰 유효성 검증 및 username 취득 포트
public interface TelegramBotInfoPort {

    // botToken으로 getMe API 호출 → 봇 username 반환. 유효하지 않은 토큰이면 IllegalArgumentException
    String getUsername(String botToken);
}
