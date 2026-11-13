package com.kista.tradingcore.privacy.adapter.in.web;

import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// privacy 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Component
class PrivacyProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(PrivacyTradeConflictException.class, new Mapping(HttpStatus.CONFLICT, "Conflict"));
    }
}
