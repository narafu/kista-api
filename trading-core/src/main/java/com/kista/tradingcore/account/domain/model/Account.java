package com.kista.tradingcore.account.domain.model;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.Broker;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record Account(
        UUID id,               // PK
        UUID userId,           // FK → users.id
        String nickname,       // 계좌 별칭
        String accountNo,      // 계좌번호 (복호화된 값) — 업비트는 null
        String appKey,      // API 앱 키 — KIS App Key / Toss Client ID / 업비트 Access Key (복호화된 값)
        String secretKey,   // API 앱 시크릿 — KIS Secret Key / Toss Client Secret / 업비트 Secret Key (복호화된 값)
        String brokerAccountCode, // 증권사 API 보조 식별자 — KIS: null(accountNo에 통합), TOSS: accountSeq
        Broker broker,            // 증권사 (기본: KIS)
        Instant createdAt,        // DB created_at, 신규 등록 시 null
        Instant apiKeyExpiresAt   // 거래소 API 키 만료 시각 — 업비트만 값
) {
    // 목록 표시용 증권사 순서 — 운영 중심(메리츠·바이낸스) 먼저, 목록에 없는 증권사는 맨 뒤
    private static final List<Broker> DISPLAY_BROKER_ORDER =
            List.of(Broker.MERITZ, Broker.BINANCE, Broker.TOSS, Broker.KIS, Broker.UPBIT, Broker.MOCK);

    // 계좌 목록 표시 순서 — 증권사 → 등록순(createdAt, 동률은 시간순 UUID v7 id)
    public static final Comparator<Account> DISPLAY_ORDER = Comparator
            .comparingInt((Account a) -> brokerRank(a.broker()))
            .thenComparing(Account::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Account::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private static int brokerRank(Broker broker) {
        int rank = DISPLAY_BROKER_ORDER.indexOf(broker);
        return rank < 0 ? DISPLAY_BROKER_ORDER.size() : rank;
    }

    // 전역 중복 판정 키 SSOT — 업비트·메리츠는 계좌번호가 없어 앱키, 그 외는 계좌번호
    public static String dedupeKeyOf(Broker broker, String accountNo, String appKey) {
        return broker.isKeyOnlyCredentials() ? appKey : accountNo;
    }

    public String dedupeKey() {
        return dedupeKeyOf(broker, accountNo, appKey);
    }

    // nickname만 교체 — AccountService.update 전용
    public Account withNickname(String newNickname) {
        return new Account(id, userId, newNickname,
                accountNo, appKey, secretKey, brokerAccountCode, broker, createdAt, apiKeyExpiresAt);
    }

    // 자격증명 교체 — 검증 결과의 보조 식별자·만료일 반영
    public Account withCredentials(String newAppKey, String newSecretKey, String newBrokerAccountCode, Instant newApiKeyExpiresAt) {
        return new Account(id, userId, nickname, accountNo, newAppKey, newSecretKey,
                newBrokerAccountCode, broker, createdAt, newApiKeyExpiresAt);
    }

    // broker 포트 시그니처용 자격증명 투영 — broker 모듈이 account.Account를 참조하지 않도록 Account가 스스로 변환한다
    // (broker↔account 순환 해소 산물: broker는 BrokerAccountRef만 알고 역방향 참조는 0. broker enum은 shared.kernel 공용)
    public BrokerAccountRef toBrokerRef() {
        return new BrokerAccountRef(id, appKey, secretKey, accountNo, brokerAccountCode, broker);
    }

    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }

    // 같은 계좌(번호, 업비트는 access key)를 중복 등록 시도한 경우
    public static class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String accountNo) {
            // 계좌 식별값은 메시지에 싣지 않는다 — detail이 사용자 응답으로 나가므로 계좌번호 원문 노출 방지
            super("이미 등록된 계좌입니다.");
        }
    }
}
