package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountInfo;
import com.kista.broker.domain.model.BrokerAccountRef;

import java.util.List;

// 증권사 계좌 목록 조회 (현재 Toss만 구현) — 계좌 토큰 필요. 벤더 중립 BrokerAccountInfo 반환
public interface BrokerAccountPort {
    List<BrokerAccountInfo> getAccountList(BrokerAccountRef account);
}
