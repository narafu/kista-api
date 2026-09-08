package com.kista.finance.application.service;

import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.finance.application.port.output.MonthlyClosingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

// 재무 쓰기(등록/수정/삭제/공유) 전에 대상 날짜가 속한 달이 기록 점검 완료 상태인지 검사하는 공용 가드.
// final 아님 — 서비스 단위테스트에서 mock 대상.
@Component
@RequiredArgsConstructor
class MonthlyClosingGuard {

    private final MonthlyClosingPort monthlyClosingPort;

    // date가 속한 달(yyyy-MM)이 기록 점검 완료 상태면 MonthClosedException.
    void verifyMonthOpen(UUID currentGroupId, UUID userId, LocalDate date) {
        String month = YearMonth.from(date).toString(); // "2026-09" — DB month 컬럼 포맷과 일치
        if (monthlyClosingPort.isMonthClosed(currentGroupId, userId, month)) {
            throw new MonthlyClosing.MonthClosedException(month);
        }
    }
}
