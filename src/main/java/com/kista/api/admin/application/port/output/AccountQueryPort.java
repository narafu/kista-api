package com.kista.api.admin.application.port.output;

import com.kista.shared.contract.account.AccountSummaryResponse;

import java.time.LocalDate;
import java.util.List;

// admin이 정의하는 account 조회 포트 — AccountQueryHttpAdapter가 내부 API로 구현
public interface AccountQueryPort {
    List<AccountSummaryResponse> findAll(LocalDate from, LocalDate to); // null = 전체
    long countAll(); // 전체 계좌 수 — AdminQueryService.getStats() 소비
}
