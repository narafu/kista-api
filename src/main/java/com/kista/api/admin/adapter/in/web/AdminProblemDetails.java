package com.kista.api.admin.adapter.in.web;

import com.kista.api.admin.domain.model.AdminBrokerCredentialException;
import com.kista.api.admin.domain.model.AdminBrokerRateLimitException;
import com.kista.api.admin.domain.model.AdminPrivacyTradeConflictException;
import com.kista.api.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// admin 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Component
class AdminProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(
                // 내부 API 응답에서 복원한 own-type — 원본은 trading-core 소유. 내부 API가 status만 전달하므로 원본과 같은 code를 다시 붙인다
                AdminBrokerCredentialException.class,     new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid Broker Credentials", ErrorCode.BROKER_CREDENTIAL_INVALID),
                AdminBrokerRateLimitException.class,      new Mapping(HttpStatus.TOO_MANY_REQUESTS, "Broker Rate Limit", ErrorCode.BROKER_RATE_LIMITED),
                AdminPrivacyTradeConflictException.class, new Mapping(HttpStatus.CONFLICT, "Conflict"),
                // trading-core 정책 API 도달 실패 — 관리자 설정 조회·갱신은 503으로 드러낸다(공개 runtime-config는 서비스가 기본값으로 강등)
                TradingPolicyUnavailableException.class,  new Mapping(HttpStatus.SERVICE_UNAVAILABLE, "Trading Core Unavailable", ErrorCode.TRADING_CORE_UNAVAILABLE));
    }
}
