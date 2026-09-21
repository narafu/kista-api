package com.kista.support;

import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.TradingUserProfile;

import java.util.Map;
import java.util.UUID;

// trading-core 테스트 공용 도메인 fixture — Account/TradingUserProfile record 필드 변경 시 이 파일만 수정
public final class TradingFixtures {

    private TradingFixtures() {}

    // trading 테스트용 TradingUserProfile — 잔고검증 ON, 알림 전부 기본값(미설정=활성)
    public static TradingUserProfile tradingUserProfile(UUID userId) {
        return new TradingUserProfile(userId, Map.of(), true, null, null);
    }

    // 기본 KIS 계좌 (accountNo/appKey/secretKey 기본값 고정)
    public static Account kisAccount(UUID id, UUID userId) {
        return new Account(id, userId, "테스트계좌", "74420614", "key", "secret", null, Broker.KIS, null);
    }

    // 기본 Toss 계좌 (accountNo/appKey/secretKey/brokerAccountCode 기본값 고정)
    public static Account tossAccount(UUID id, UUID userId) {
        return new Account(id, userId, "테스트계좌", "123-45-678901", "key", "secret", "1", Broker.TOSS, null);
    }
}
