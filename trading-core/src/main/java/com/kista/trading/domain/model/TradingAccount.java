package com.kista.trading.domain.model;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.sharedkernel.Broker;

import java.util.UUID;

// 배치·프리뷰·리포트 경로가 쓰는 계좌 4필드 투영 — Account 애그리게이트(자격증명·소유권 검증)를 실행 경로에 흘리지 않기 위한 trading 소유 record
// id/userId/nickname: 식별·알림 표기 / brokerRef: 브로커 포트 호출용 자격증명(BrokerAccountRef.toString은 마스킹)
// 타입 표면 축소가 목적이다 — 자격증명 은닉이 아니다
public record TradingAccount(UUID id, UUID userId, String nickname, BrokerAccountRef brokerRef) {

    // Account 애그리게이트 → 배치 경로 투영 (from() 정의 1곳, 호출 경계 3곳: BatchContextFactory / ManualTradingService / TradingPreviewService)
    public static TradingAccount from(Account account) {
        return new TradingAccount(account.id(), account.userId(), account.nickname(), account.toBrokerRef());
    }

    // 라우팅 키 단축 접근자 — account.brokerRef().broker() 체인 대체
    public Broker broker() {
        return brokerRef.broker();
    }
}
