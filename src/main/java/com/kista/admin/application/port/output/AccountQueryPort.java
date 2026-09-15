package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminAccountView;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// admin이 정의하는 account 조회 포트 — AccountQueryHttpAdapter가 내부 API로 구현
public interface AccountQueryPort {
    List<AdminAccountView> findAll(LocalDate from, LocalDate to); // null = 전체
    Optional<AdminAccountView> findById(UUID accountId);
    long countAll(); // 전체 계좌 수 — AdminQueryService.getStats() 소비
}
