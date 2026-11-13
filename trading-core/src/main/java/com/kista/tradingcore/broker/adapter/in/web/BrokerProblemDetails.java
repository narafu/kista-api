package com.kista.tradingcore.broker.adapter.in.web;

import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ExceptionReporter;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.shared.platform.web.ProblemResponder;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.broker.domain.model.BrokerWithdrawalEnabledException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.kista.shared.platform.web.ProblemDetailMappings.problem;

// broker 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Slf4j
@Component
class BrokerProblemDetails implements ProblemDetailMappingContributor {

    private static final String BROKER_UNAVAILABLE_DETAIL = "증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요."; // 503 응답 detail

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(
                BrokerWithdrawalEnabledException.class, new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "Broker Withdrawal Enabled", ErrorCode.BROKER_WITHDRAWAL_ENABLED),
                BrokerIpNotAllowedException.class,      new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "Broker IP Not Allowed", ErrorCode.BROKER_IP_NOT_ALLOWED),
                BrokerPermissionDeniedException.class,  new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "Broker Permission Denied", ErrorCode.BROKER_PERMISSION_DENIED),
                BrokerCredentialException.class,        new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid Broker Credentials", ErrorCode.BROKER_CREDENTIAL_INVALID),
                BrokerRateLimitException.class,         new Mapping(HttpStatus.TOO_MANY_REQUESTS, "Broker Rate Limit", ErrorCode.BROKER_RATE_LIMITED));
    }

    // 증권사 API 실패는 벤더 표기 title이 동적이고 오류 보고가 필요해 표로 못 나타낸다
    @Override
    public Map<Class<? extends Exception>, ProblemResponder> responders() {
        return Map.of(BrokerApiException.class, (ex, reporter) -> brokerUnavailable((BrokerApiException) ex, reporter));
    }

    // 모든 증권사 API 실패를 벤더 중립 503으로 — title은 벤더 표기("KIS API Error"/"Toss API Error"), 저장은 보고 빈에 위임
    private static ResponseEntity<ProblemDetail> brokerUnavailable(BrokerApiException ex, ExceptionReporter reporter) {
        reporter.report(ex);
        log.error("{} API 오류: {}", ex.vendorLabel(), ex.getMessage(), ex);
        // code=BROKER_UNAVAILABLE이 kista-ui relayUpstreamError의 503 relay 판별 계약 — title은 표시·로그용 벤더 표기일 뿐 계약 아님
        // detail은 사용자 노출용 고정 문구 — 원본 메시지(응답 바디·accountId 등 내부 정보)는 로그·에러 로그에만 남긴다
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, ex.vendorLabel() + " API Error", BROKER_UNAVAILABLE_DETAIL, ErrorCode.BROKER_UNAVAILABLE));
    }
}
