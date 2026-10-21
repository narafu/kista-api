package com.kista.tradingcore.support;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;

import java.math.BigDecimal;
import java.util.List;
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

    // 기본 업비트 계좌 — 계좌번호 없음(access key가 중복 판정 키)
    public static Account upbitAccount(UUID id, UUID userId) {
        return new Account(id, userId, "업비트", null, "access-key", "secret-key", null, Broker.UPBIT, null);
    }

    // 배치 경로용 KIS 계좌 투영 — kisAccount와 동일 값
    public static TradingAccount tradingAccount(UUID id, UUID userId) {
        return TradingAccount.from(kisAccount(id, userId));
    }

    // 배치 경로용 Toss 계좌 투영 — tossAccount와 동일 값
    public static TradingAccount tossTradingAccount(UUID id, UUID userId) {
        return TradingAccount.from(tossAccount(id, userId));
    }

    // 주식 DCA 규칙 — 매일·고정 금액·PARTIAL(기존 단일 규칙 동작과 같은 부족 처리)
    public static DcaRule stockDcaRule(String ruleKey, OrderDirection direction, String amount, OrderType orderType,
                                       DcaCondition... conditions) {
        return new DcaRule(ruleKey, direction, DcaFrequency.DAILY, null, null, orderType, null,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, List.of(conditions));
    }

    // 코인 DCA 규칙 — 매일·고정 금액(원)·PARTIAL, executionHour 시각 실행
    public static DcaRule cryptoDcaRule(String ruleKey, OrderDirection direction, String amount, int executionHour,
                                        DcaCondition... conditions) {
        return new DcaRule(ruleKey, direction, DcaFrequency.DAILY, null, null, OrderType.MARKET, executionHour,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, List.of(conditions));
    }

    public static DcaStrategyConfig dcaConfig(DcaRule... rules) {
        return new DcaStrategyConfig(List.of(rules));
    }
}
