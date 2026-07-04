package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.OrderCancelException;
import com.kista.domain.model.privacy.PrivacyTradeConflictException;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.AppErrorLogPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;
import java.util.NoSuchElementException;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AppErrorLogPort appErrorLogPort;

    // ── 4xx — 클라이언트 오류, DB 저장 없음 ─────────────────────────────────────

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        // refresh 인증 실패 — AuthController Swagger 401 명세와 실제 동작 일치
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ProblemDetail handleForbidden(SecurityException ex) {
        return problem(HttpStatus.FORBIDDEN, "Access Denied", ex.getMessage());
    }

    @ExceptionHandler(User.CooldownException.class)
    public ResponseEntity<ProblemDetail> handleCooldown(User.CooldownException ex) {
        // Retry-After 헤더에 재신청 가능 시각(Unix epoch 초) 포함
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "Cooldown Active", ex.getMessage());
        detail.setProperty("retryAfter", ex.getRetryAfter().toString());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().getEpochSecond()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(detail);
    }

    @ExceptionHandler(Account.InvalidBrokerKeyException.class)
    public ProblemDetail handleInvalidBrokerKey(Account.InvalidBrokerKeyException ex) {
        // 증권사 자격증명 검증 실패 (KIS/Toss 공통) → 422 UNPROCESSABLE_ENTITY
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Broker Credentials", ex.getMessage());
    }

    @ExceptionHandler(Account.KisRateLimitException.class)
    public ProblemDetail handleKisRateLimit(Account.KisRateLimitException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "KIS Rate Limit", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid State", ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource Not Found", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList()
                .toString();
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", ex.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleMissingParam(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler(DateTimeParseException.class)
    public ProblemDetail handleDateTimeParse(DateTimeParseException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Date Format", ex.getMessage());
    }

    @ExceptionHandler({Account.DuplicateAccountException.class, ManualTradingException.class,
                       OrderCancelException.class, PrivacyTradeConflictException.class})
    public ProblemDetail handleConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    // ── 5xx — 서버 오류, DB 저장 ────────────────────────────────────────────────

    @ExceptionHandler(KisApiException.class)
    public ProblemDetail handleKisApiException(KisApiException ex) {
        saveErrorLog(ex);
        log.error("KIS API 오류: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "KIS API Error", ex.getMessage());
    }

    @ExceptionHandler(TossApiException.class)
    public ProblemDetail handleTossApiException(TossApiException ex) {
        saveErrorLog(ex);
        log.error("Toss API 오류: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Toss API Error", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        saveErrorLog(ex);
        log.error("미처리 예외 발생: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "예기치 않은 오류가 발생했습니다");
    }

    // ProblemDetail 생성 헬퍼 — 모든 핸들러에서 반복되는 3줄 보일러플레이트 제거
    private static ProblemDetail problem(HttpStatus status, String title, String msg) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, msg);
        detail.setTitle(title);
        return detail;
    }

    // DB 저장 실패가 원래 응답을 막지 않도록 격리
    private void saveErrorLog(Exception e) {
        try {
            appErrorLogPort.save(e, "GlobalExceptionHandler");
        } catch (Exception saveEx) {
            log.warn("오류 로그 저장 실패: {}", saveEx.getMessage());
        }
    }
}
