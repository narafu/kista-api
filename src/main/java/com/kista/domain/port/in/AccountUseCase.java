package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;

import java.util.List;
import java.util.UUID;

public interface AccountUseCase {
    // --- 조회 ---
    List<Account> listByUser(UUID userId);
    Account getById(UUID id);

    // --- 등록 ---
    Account register(UUID userId, RegisterAccountCommand command);

    // --- 수정 ---
    Account update(UUID accountId, UUID requesterId, UpdateAccountCommand command);

    // --- 삭제 ---
    void delete(UUID accountId, UUID requesterId);

    // --- 증권사 연결 테스트 ---
    // accountId null 허용 — null이면 캐시 저장 생략 (등록 전 사전 검증). 실패 시 Account.InvalidKisKeyException throw
    void test(Account.Broker broker, String appKey, String appSecret, UUID accountId);
}
