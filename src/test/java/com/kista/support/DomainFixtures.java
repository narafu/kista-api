package com.kista.support;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;

import java.util.UUID;

// 테스트 공용 도메인 fixture — User/Account record 필드 변경 시 이 파일만 수정
public final class DomainFixtures {

    private DomainFixtures() {}

    // 기본 ACTIVE 사용자 (텔레그램 미설정, 알림채널 지정 가능)
    public static User activeUser(UUID id, NotificationChannel channel) {
        return new User(id, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, channel);
    }

    // 기본 ACTIVE 사용자 (알림채널 TELEGRAM 기본값 — 스케쥴러 테스트용)
    public static User activeUser(UUID id) {
        return activeUser(id, NotificationChannel.TELEGRAM);
    }

    // 텔레그램 설정된 사용자 — 알림 어댑터 테스트용 (botUsername은 null 고정)
    public static User telegramUser(UUID id, String botToken, String chatId) {
        return activeUser(id, NotificationChannel.TELEGRAM).withTelegram(botToken, chatId, null);
    }

    // 기본 KIS 계좌 (accountNo/appKey/secretKey 기본값 고정)
    public static Account kisAccount(UUID id, UUID userId) {
        return new Account(id, userId, "테스트계좌", "74420614", "key", "secret", null, Account.Broker.KIS, null);
    }
}
