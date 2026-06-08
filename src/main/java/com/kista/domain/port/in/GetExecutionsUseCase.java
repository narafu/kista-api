package com.kista.domain.port.in;

import com.kista.domain.model.kis.Execution;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// KIS TTTS3035R — 기간 체결 내역 조회
public interface GetExecutionsUseCase {
    List<Execution> getExecutions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
}
