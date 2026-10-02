package com.kista.platform.telegram;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// root(:api)와 trading-core 두 프로세스가 같은 관리자 텔레그램 봇을 공유한다 — 둘 다 동일한
// TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID 환경변수를 읽어 같은 채팅방으로 발송한다(의도된 중복).
// 관리자 오류 알림 경로라 누락·빈값이면 기동 실패시킨다 — 배포 헬스 게이트가 실패해 자동 롤백된다
@Validated
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(@NotBlank String botToken, @NotBlank String chatId) {
}
