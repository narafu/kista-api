package com.kista.tradingcore.account.application.usecase;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.domain.model.RegisterAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand;

import com.kista.shared.kernel.Broker;
import java.util.List;
import java.util.UUID;

public interface AccountUseCase {
    // --- 조회 ---
    List<Account> listByUser(UUID userId);

    // --- 등록 ---
    Account register(UUID userId, RegisterAccountCommand command);

    // --- 수정 ---
    Account update(UUID accountId, UUID requesterId, UpdateAccountCommand command);

    // 계좌 API 키 교체 — 소유권 → 연결 테스트 통과 시 저장·만료일 갱신. 실패 시 BrokerCredentialException(422)
    Account updateCredentials(UUID accountId, UUID requesterId, UpdateCredentialsCommand command);

    // --- 삭제 ---
    void delete(UUID accountId, UUID requesterId);
    // 사용자 탈퇴 정리 — 증권사 토큰 하드 삭제 + 계좌 일괄 소프트 삭제 (호출자 트랜잭션 합류)
    void deleteAllByUserId(UUID userId);

    // --- 증권사 연결 테스트 ---
    // 등록 전 사전 검증 — 어느 계좌의 토큰 캐시도 건드리지 않는다. 실패 시 BrokerCredentialException throw
    void test(Broker broker, String appKey, String appSecret);
}
