package com.kista.tradingcore.support;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// trading-core 테스트 공용 도메인 fixture — Account/TradingUserProfile record 필드 변경 시 이 파일만 수정
public final class TradingFixtures {

    private TradingFixtures() {}

    // 계좌당 활성 종목 유니크 인덱스 때문에 전략마다 다른 종목이 필요한 영속 테스트용 — 테스트 인스턴스마다 new로 0부터, 이미 쓴 종목은 제외
    public static final class TickerSequence {
        private final List<StrategyTicker> candidates;
        private int seq;

        public TickerSequence(StrategyTicker... excluded) {
            candidates = Arrays.stream(StrategyTicker.values()).filter(t -> !List.of(excluded).contains(t)).toList();
        }

        public StrategyTicker next() {
            return candidates.get(seq++ % candidates.size());
        }
    }

    // trading 테스트용 TradingUserProfile — 잔고검증 ON, 알림 전부 기본값(미설정=활성)
    public static TradingUserProfile tradingUserProfile(UUID userId) {
        return new TradingUserProfile(userId, Map.of(), null, null);
    }

    // 기본 주식 계좌(TOSS, brokerAccountCode 없음 — accountNo/appKey/secretKey 기본값 고정)
    public static Account stockAccount(UUID id, UUID userId) {
        return new Account(id, userId, "테스트계좌", "74420614", "key", "secret", null, Broker.TOSS, null, null);
    }

    // 기본 Toss 계좌 (accountNo/appKey/secretKey/brokerAccountCode 기본값 고정)
    public static Account tossAccount(UUID id, UUID userId) {
        return new Account(id, userId, "테스트계좌", "123-45-678901", "key", "secret", "1", Broker.TOSS, null, null);
    }

    // 배치 경로용 주식 계좌 투영 — stockAccount와 동일 값
    public static TradingAccount tradingAccount(UUID id, UUID userId) {
        return TradingAccount.from(stockAccount(id, userId));
    }

    // 배치 경로용 Toss 계좌 투영 — tossAccount와 동일 값
    public static TradingAccount tossTradingAccount(UUID id, UUID userId) {
        return TradingAccount.from(tossAccount(id, userId));
    }

    // 주식 DCA 규칙 — 매일·고정 금액·PARTIAL(기존 단일 규칙 동작과 같은 부족 처리)
    public static DcaRule stockDcaRule(String ruleKey, OrderDirection direction, String amount, OrderType orderType,
                                       DcaCondition... conditions) {
        return new DcaRule(ruleKey, direction, DcaFrequency.DAILY, null, null, orderType,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, List.of(conditions));
    }

    public static DcaStrategyConfig dcaConfig(DcaRule... rules) {
        return new DcaStrategyConfig(List.of(rules));
    }
}
