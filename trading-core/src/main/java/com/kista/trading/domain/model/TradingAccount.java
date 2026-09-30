package com.kista.trading.domain.model;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;

import java.util.UUID;

// 배치·프리뷰·리포트 경로가 쓰는 계좌 4필드 투영 — Account 애그리게이트(자격증명·소유권 검증)를 실행 경로에 흘리지 않기 위한 trading 소유 record
// id/userId/nickname: 식별·알림 표기 / brokerRef: 브로커 포트 호출용 자격증명(자격증명은 이 안에만 존재)
public record TradingAccount(UUID id, UUID userId, String nickname, BrokerAccountRef brokerRef) {

    // Account 애그리게이트 → 배치 경로 투영 (Account 참조는 이 변환 1곳뿐)
    public static TradingAccount from(Account account) {
        return new TradingAccount(account.id(), account.userId(), account.nickname(), account.toBrokerRef());
    }
}
