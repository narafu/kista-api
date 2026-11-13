package com.kista.api.user.adapter.in.web;

import com.kista.api.user.domain.auth.InvalidRefreshTokenException;
import com.kista.api.user.domain.model.User;
import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.shared.platform.web.ProblemResponder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.kista.shared.platform.web.ProblemDetailMappings.problem;

// user 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Component
class UserProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(
                InvalidRefreshTokenException.class, new Mapping(HttpStatus.UNAUTHORIZED, "Unauthorized"),
                User.AlreadyActiveException.class,  new Mapping(HttpStatus.CONFLICT, "Conflict"));
    }

    // 쿨다운은 Retry-After 헤더가 필요해 표로 못 나타낸다
    @Override
    public Map<Class<? extends Exception>, ProblemResponder> responders() {
        return Map.of(User.CooldownException.class, (ex, reporter) -> cooldown((User.CooldownException) ex));
    }

    // 429 + Retry-After 헤더(재신청 가능 시각 Unix epoch 초) + retryAfter 프로퍼티(ISO)
    private static ResponseEntity<ProblemDetail> cooldown(User.CooldownException ex) {
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "Cooldown Active", ex.getMessage(), ErrorCode.COOLDOWN_ACTIVE);
        detail.setProperty("retryAfter", ex.getRetryAfter().toString());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().getEpochSecond()))
                .body(detail);
    }
}
