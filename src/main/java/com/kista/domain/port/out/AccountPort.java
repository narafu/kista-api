package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface AccountPort {
    List<Account> findByUserId(UUID userId);
    Optional<Account> findById(UUID id);

    // 없으면 NoSuchElementException — findById + orElseThrow 반복 제거용
    default Account findByIdOrThrow(UUID accountId) {
        return findById(accountId).orElseThrow(
                () -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
    }

    // 계좌 조회 + 소유권 검증 — 불일치 시 SecurityException (컨트롤러에서 403 변환)
    default Account requireOwnedAccount(UUID accountId, UUID requesterId) {
        Account account = findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return account;
    }
    int countByUserId(UUID userId);
    // 전역 계좌번호 중복 체크 — 플레인텍스트 전달, 해시 계산은 adapter 내부에서 처리
    boolean existsByAccountNo(String accountNo);
    Account save(Account account);
    void delete(UUID id);
    void deleteByUserId(UUID userId); // 사용자 탈퇴 시 계좌 일괄 소프트 삭제
    long countAll(); // 전체 계좌 수 (대시보드 통계용)
    List<Account> findAll(); // 전체 계좌 목록 (관리자용)
}
