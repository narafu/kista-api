package com.kista.broker.adapter.out.mock;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.application.port.output.BrokerConnectionTestPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 모의계좌 자격증명 검증 — 실제 증권사 API 호출 없이 항상 통과 (자격증명이 존재하지 않는 계좌이므로 검증 대상 자체가 없음)
@Component
class MockAuthApi implements BrokerConnectionTestPort {

    @Override
    public BrokerAccountRef.Broker supports() {
        return BrokerAccountRef.Broker.MOCK;
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey, UUID accountId) {
        // no-op — 모의계좌는 실제 자격증명이 없어 검증할 대상이 없음
    }

    @Override
    public String verifyAccount(String appKey, String secretKey, String accountNo) {
        // KIS와 동일하게 brokerAccountCode 없음
        return null;
    }
}
