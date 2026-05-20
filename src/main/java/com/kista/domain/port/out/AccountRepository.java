package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    List<Account> findByUserId(UUID userId);
    Optional<Account> findById(UUID id);

    // 없으면 NoSuchElementException — findById + orElseThrow 반복 제거용
    default Account findByIdOrThrow(UUID accountId) {
        return findById(accountId).orElseThrow(
                () -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
    }
    int countByUserId(UUID userId);
    // ACTIVE 사용자의 ACTIVE 계좌 전체 조회 (스케줄러용)
    List<Account> findAllActive();
    Account save(Account account);
    void delete(UUID id);
    long countAll(); // 전체 계좌 수 (대시보드 통계용)
    List<Account> findAll(); // 전체 계좌 목록 (관리자용)
}
