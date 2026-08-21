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
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return monthlyClosingPort.findMyScope(userId, currentGroupId);
    }

    @Override
    public MonthlyClosing setCompleted(UUID userId, UUID requestedGroupId, String month, boolean completed) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 형식·범위(월 01~12) 동시 검증 — 실패 시 DateTimeParseException → GlobalExceptionHandler가 400으로 매핑
        YearMonth.parse(month, MONTH_FORMATTER);
        // userId는 영구 소유자 축 — 마감 해제해도 null로 되돌리지 않고 항상 실제 userId를 넘긴다.
        // currentGroupId가 있으면 그룹 마감, 없으면 개인 마감이 된다.
        return monthlyClosingPort.upsert(currentGroupId, userId, month, completed);
    }
}
