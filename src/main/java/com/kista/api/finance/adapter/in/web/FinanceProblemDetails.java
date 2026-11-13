package com.kista.api.finance.adapter.in.web;

import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceBudget;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceGroup;
import com.kista.api.finance.domain.model.FinanceGroupInvitation;
import com.kista.api.finance.domain.model.MonthlyClosing;
import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// finance 모듈 예외의 HTTP 응답(전부 409) — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Component
class FinanceProblemDetails implements ProblemDetailMappingContributor {

    private static final Mapping CONFLICT = new Mapping(HttpStatus.CONFLICT, "Conflict"); // detail은 예외 메시지

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(
                FinanceBudget.OverlappingPeriodException.class,               CONFLICT,
                FinanceAccount.DuplicateAccountNoException.class,             CONFLICT,
                FinanceAccount.LinkedAssetSnapshotsException.class,           CONFLICT,
                FinanceCategory.DuplicateNameException.class,                 CONFLICT,
                FinanceGroupInvitation.InvalidInvitationStateException.class, CONFLICT,
                FinanceGroup.MembershipConflictException.class,               CONFLICT,
                MonthlyClosing.MonthClosedException.class,                    new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.MONTH_CLOSED));
    }
}
