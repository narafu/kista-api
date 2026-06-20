package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossAccountInfo;

import java.util.List;

public interface TossAccountListPort {
    // GET /api/v1/accounts — 토큰에 연결된 계좌 목록 전체 반환
    List<TossAccountInfo> getAccountList(Account account);
}
