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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
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

    // 단순 status·title 매핑 테이블 — 엔트리 1줄 추가만으로 신규 예외 확장 (catch-all이 테이블 조회 통합)
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

    // SSE 타임아웃·연결 종료는 이미 끝난 스트림에 별도 응답 본문을 쓰지 않고 종료 처리
    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void handleAsyncLifecycle(Exception ex) {
        log.debug("SSE async request 종료: {}", ex.getClass().getSimpleName());
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

    // catch-all — MAPPINGS 테이블 우선 조회, 매핑 있으면 4xx 응답(saveErrorLog 없음) / 없으면 500 처리
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAll(Exception ex) {
        // 매핑 테이블 조회 — 클래스 계층 탐색으로 서브클래스도 상위 매핑 적용
        Mapping m = resolveMapping(ex);
        if (m != null) {
            // 4xx 예외: saveErrorLog 없이 응답 — 기존 handleMapped 동작 보존
            return problem(m.status(), m.title(), ex.getMessage());
        }
        // 매핑 없는 미처리 예외 — saveErrorLog + log.error + 500
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
