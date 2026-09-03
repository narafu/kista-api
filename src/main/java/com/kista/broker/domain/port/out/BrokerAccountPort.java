package com.kista.broker.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.toss.TossAccountInfo;

import java.util.List;

// 증권사 계좌 목록 조회 (Toss 전용) — 계좌 토큰 필요
public interface BrokerAccountPort {
    List<TossAccountInfo> getAccountList(Account account);
}
