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
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AppErrorLogPort appErrorLogPort;

    // status·title 쌍 튜플 — 테이블 값 타입
    private record Mapping(HttpStatus status, String title) {}

    // 단순 status·title 매핑 테이블 — 신규 예외 추가 시 엔트리 1줄만 추가
    private static final Map<Class<? extends Exception>, Mapping> MAPPINGS = Map.ofEntries(
        Map.entry(InvalidRefreshTokenException.class,              new Mapping(HttpStatus.UNAUTHORIZED,           "Unauthorized")),
        Map.entry(SecurityException.class,                         new Mapping(HttpStatus.FORBIDDEN,              "Access Denied")),
        Map.entry(Account.InvalidBrokerKeyException.class,         new Mapping(HttpStatus.UNPROCESSABLE_ENTITY,   "Invalid Broker Credentials")),
        Map.entry(Account.KisRateLimitException.class,             new Mapping(HttpStatus.TOO_MANY_REQUESTS,      "KIS Rate Limit")),
        Map.entry(IllegalStateException.class,                     new Mapping(HttpStatus.BAD_REQUEST,            "Invalid State")),
        Map.entry(NoSuchElementException.class,                    new Mapping(HttpStatus.NOT_FOUND,              "Resource Not Found")),
        Map.entry(IllegalArgumentException.class,                  new Mapping(HttpStatus.BAD_REQUEST,            "Invalid Request")),
        Map.entry(MissingServletRequestParameterException.class,   new Mapping(HttpStatus.BAD_REQUEST,            "Bad Request")),
        Map.entry(MethodArgumentTypeMismatchException.class,       new Mapping(HttpStatus.BAD_REQUEST,            "Bad Request")),
        Map.entry(DateTimeParseException.class,                    new Mapping(HttpStatus.BAD_REQUEST,            "Invalid Date Format")),
        Map.entry(Account.DuplicateAccountException.class,         new Mapping(HttpStatus.CONFLICT,               "Conflict")),
        Map.entry(ManualTradingException.class,                    new Mapping(HttpStatus.CONFLICT,               "Conflict")),
        Map.entry(OrderCancelException.class,                      new Mapping(HttpStatus.CONFLICT,               "Conflict")),
        Map.entry(PrivacyTradeConflictException.class,             new Mapping(HttpStatus.CONFLICT,               "Conflict"))
    );

    // MAPPINGS 테이블에 등록된 예외를 단일 핸들러로 처리 — 부가 로직 없는 단순 status·title 매핑만
    @ExceptionHandler({
        InvalidRefreshTokenException.class,
        SecurityException.class,
        Account.InvalidBrokerKeyException.class,
        Account.KisRateLimitException.class,
        IllegalStateException.class,
        NoSuchElementException.class,
        IllegalArgumentException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        DateTimeParseException.class,
        Account.DuplicateAccountException.class,
        ManualTradingException.class,
        OrderCancelException.class,
        PrivacyTradeConflictException.class
    })
    public ProblemDetail handleMapped(Exception ex) {
        // 클래스 계층 탐색 — 서브클래스가 전달돼도 상위 매핑으로 fallback
        Mapping m = resolveMapping(ex);
        if (m == null) {
            // @ExceptionHandler 목록과 MAPPINGS가 불일치하면 내부 오류로 처리
            log.error("예외 매핑 누락: {}", ex.getClass().getName(), ex);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ex.getMessage());
        }
        return problem(m.status(), m.title(), ex.getMessage());
    }

    // Retry-After 헤더 포함 — 단순 ProblemDetail 반환 불가, 개별 유지
    @ExceptionHandler(User.CooldownException.class)
    public ResponseEntity<ProblemDetail> handleCooldown(User.CooldownException ex) {
        // Retry-After 헤더에 재신청 가능 시각(Unix epoch 초) 포함
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "Cooldown Active", ex.getMessage());
        detail.setProperty("retryAfter", ex.getRetryAfter().toString());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().getEpochSecond()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(detail);
    }

    // 필드 오류 메시지 집계 — 부가 로직 있으므로 개별 유지
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList()
                .toString();
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", message);
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

    // 클래스 계층 탐색 — 서브클래스 예외도 상위 매핑으로 처리 가능
    private static Mapping resolveMapping(Exception ex) {
        Class<?> cls = ex.getClass();
        while (cls != null && Exception.class.isAssignableFrom(cls)) {
            @SuppressWarnings("unchecked")
            Mapping m = MAPPINGS.get((Class<? extends Exception>) cls);
            if (m != null) return m;
            cls = cls.getSuperclass();
        }
        return null;
    }
}
