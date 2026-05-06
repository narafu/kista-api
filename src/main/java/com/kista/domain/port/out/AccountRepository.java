package com.kista.domain.port.out;

import com.kista.domain.model.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    List<Account> findByUserId(UUID userId);
    Optional<Account> findById(UUID id);
    int countByUserId(UUID userId);
    // ACTIVE 사용자의 ACTIVE 계좌 전체 조회 (스케줄러용)
    List<Account> findAllActive();
    Account save(Account account);
    void delete(UUID id);
}
