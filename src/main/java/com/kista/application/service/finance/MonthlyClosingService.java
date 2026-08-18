package com.kista.application.service.finance;

import com.kista.domain.model.finance.MonthlyClosing;
import com.kista.domain.port.in.MonthlyClosingUseCase;
import com.kista.domain.port.out.FinanceGroupPort;
import com.kista.domain.port.out.MonthlyClosingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

// 구 AssetMonthlyCheckService 대체 — 자산뿐 아니라 재무 전 영역(수입/소비/저축/자산)의 월 마감을 덮는다.
@Service
@RequiredArgsConstructor
@Transactional
class MonthlyClosingService implements MonthlyClosingUseCase {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MonthlyClosingPort monthlyClosingPort;
    private final FinanceGroupPort financeGroupPort;

    @Override
    @Transactional(readOnly = true)
    public List<MonthlyClosing> list(UUID userId, UUID requestedGroupId) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        return monthlyClosingPort.findByGroupId(groupId);
    }

    @Override
    public MonthlyClosing setCompleted(UUID userId, UUID requestedGroupId, String month, boolean completed) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        // 형식·범위(월 01~12) 동시 검증 — 실패 시 DateTimeParseException → GlobalExceptionHandler가 400으로 매핑
        YearMonth.parse(month, MONTH_FORMATTER);
        // 마감 해제 시 closedBy는 null — 어댑터가 closed_at도 함께 null로 되돌린다.
        return monthlyClosingPort.upsert(groupId, completed ? userId : null, month, completed);
    }
}
