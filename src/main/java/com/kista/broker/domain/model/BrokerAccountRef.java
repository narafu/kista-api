package com.kista.broker.domain.model;

import com.kista.sharedkernel.Broker;

import java.util.UUID;

// 계좌 자격증명 + 라우팅 키 — account.Account 전체를 broker 포트 시그니처에 노출하지 않기 위한 broker 소유 투영 record
// broker 모듈은 이 파일에서조차 account.Account를 참조하지 않는다 — Account → BrokerAccountRef 변환은 account 쪽(Account.toBrokerRef())이 담당한다
// Broker enum은 sharedkernel 공용 타입(과거 nested 복제는 삭제, record 자체만 own-type으로 존속)
public record BrokerAccountRef(
        UUID id,                  // 토큰 캐싱 키
        String appKey,
        String secretKey,
        String accountNo,
        String brokerAccountCode, // KIS: null, TOSS: accountSeq
        Broker broker              // 라우팅 키
) {
}
