package com.kista.tradingcore.account.application.port.output;

import com.kista.tradingcore.account.domain.model.Account;

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
                () -> new NoSuchElementException("계좌를 찾을 수 없습니다."));
    }

    // 계좌 조회 + 소유권 검증 — 불일치 시 SecurityException (컨트롤러에서 403 변환)
    default Account requireOwnedAccount(UUID accountId, UUID requesterId) {
        Account account = findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return account;
    }
    int countByUserId(UUID userId);
    // 전역 중복 체크 — Account.dedupeKey() 값(업비트 access key / 그 외 계좌번호)의 해시로 조회, 해시 계산은 adapter 내부
    boolean existsByDedupeKey(String dedupeKey);
    Account save(Account account);
    void delete(UUID id);
    void deleteByUserId(UUID userId); // 사용자 탈퇴 시 계좌 일괄 소프트 삭제
    long countAll(); // 전체 계좌 수 (대시보드 통계용)
    List<Account> findAll(); // 전체 계좌 목록 (관리자용)
}
