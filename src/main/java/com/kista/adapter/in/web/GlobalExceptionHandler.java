package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.toss.TossApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.OrderCancelException;
import com.kista.domain.model.privacy.PrivacyTradeConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public ProblemDetail handleForbidden(SecurityException ex) {
        return problem(HttpStatus.FORBIDDEN, "Access Denied", ex.getMessage());
    }

    @ExceptionHandler(Account.CooldownException.class)
    public ResponseEntity<ProblemDetail> handleCooldown(Account.CooldownException ex) {
        // Retry-After 헤더에 재신청 가능 시각(Unix epoch 초) 포함
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "Cooldown Active", ex.getMessage());
        detail.setProperty("retryAfter", ex.getRetryAfter().toString());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().getEpochSecond()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(detail);
    }

    @ExceptionHandler(Account.InvalidKisKeyException.class)
    public ProblemDetail handleInvalidKisKey(Account.InvalidKisKeyException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid KIS Credentials", ex.getMessage());
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

    @ExceptionHandler(Account.DuplicateAccountException.class)
    public ProblemDetail handleDuplicateAccount(Account.DuplicateAccountException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate Account", ex.getMessage());
    }

    @ExceptionHandler(ManualTradingException.class)
    public ProblemDetail handleManualTrading(ManualTradingException ex) {
        return problem(HttpStatus.CONFLICT, "Manual Trading Conflict", ex.getMessage());
    }

    @ExceptionHandler(OrderCancelException.class)
    public ProblemDetail handleOrderCancel(OrderCancelException ex) {
        return problem(HttpStatus.CONFLICT, "Order Cancel Conflict", ex.getMessage());
    }

    @ExceptionHandler(PrivacyTradeConflictException.class)
    public ProblemDetail handlePrivacyTradeConflict(PrivacyTradeConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Privacy Trade Conflict", ex.getMessage());
    }

    @ExceptionHandler(KisApiException.class)
    public ProblemDetail handleKisApiException(KisApiException ex) {
        log.error("KIS API 오류: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "KIS API Error", ex.getMessage());
    }

    @ExceptionHandler(TossApiException.class)
    public ProblemDetail handleTossApiException(TossApiException ex) {
        log.error("Toss API 오류: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Toss API Error", ex.getMessage());
    }

    // ProblemDetail 생성 헬퍼 — 모든 핸들러에서 반복되는 3줄 보일러플레이트 제거
    private static ProblemDetail problem(HttpStatus status, String title, String msg) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, msg);
        detail.setTitle(title);
        return detail;
    }
}
