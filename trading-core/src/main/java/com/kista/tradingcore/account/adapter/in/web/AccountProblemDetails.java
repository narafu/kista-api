package com.kista.tradingcore.account.adapter.in.web;

import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.tradingcore.account.domain.model.Account;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// account 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Component
class AccountProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(Account.DuplicateAccountException.class, new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.DUPLICATE_ACCOUNT));
    }
}
