package com.kista.web;

import com.kista.admin.domain.model.AdminBrokerCredentialException;
import com.kista.admin.domain.model.AdminBrokerRateLimitException;
import com.kista.admin.domain.model.AdminPrivacyTradeConflictException;
import com.kista.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.finance.domain.model.FinanceAccount;
import com.kista.finance.domain.model.FinanceBudget;
import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceGroupInvitation;
import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.platform.web.ErrorCode;
import com.kista.platform.web.ProblemDetailMappings;
import com.kista.platform.web.ProblemDetailMappings.Mapping;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import com.kista.user.domain.auth.InvalidRefreshTokenException;
import com.kista.user.domain.model.User;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.Map;

import static com.kista.platform.web.ProblemDetailMappings.problem;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 500 오류 보고 이벤트 발행용 — 저장은 admin AppErrorRaisedListener가 담당(web은 admin 포트를 모른다)
    private final ApplicationEventPublisher eventPublisher;

    // root 고유 status·title 매핑 테이블 — 엔트리 1줄 추가만으로 신규 예외 확장 (catch-all이 테이블 조회 통합).
    // JDK/Spring 범용 예외(SecurityException·IllegalStateException·NoSuchElementException 등)는 ProblemDetailMappings.GENERIC이 공급한다
    private static final Map<Class<? extends Exception>, Mapping> MAPPINGS = ProblemDetailMappings.withGeneric(Map.ofEntries(
        Map.entry(InvalidRefreshTokenException.class,              new Mapping(HttpStatus.UNAUTHORIZED,           "Unauthorized")),
        // broker.domain.model.BrokerCredentialException/BrokerRateLimitException 원본은
        // trading-core 네이티브 컨트롤러(AccountController 등)에서만 던져지므로 trading-core 소유
        // com.kista.tradingweb.TradingExceptionHandler로 이관됨 — 아래는 admin이
        // TradingCommandHttpAdapter(내부 API 응답 복원)에서 던지는 own-type만 남는다
        // 코드는 trading-core 원본(BrokerCredential/RateLimit)과 동일 — 내부 API가 status만 전달하므로 root가 같은 코드를 다시 붙인다
        Map.entry(AdminBrokerCredentialException.class,             new Mapping(HttpStatus.UNPROCESSABLE_ENTITY,   "Invalid Broker Credentials", ErrorCode.BROKER_CREDENTIAL_INVALID)),
        Map.entry(AdminBrokerRateLimitException.class,              new Mapping(HttpStatus.TOO_MANY_REQUESTS,      "KIS Rate Limit", ErrorCode.BROKER_RATE_LIMITED)),
        // trading-core 정책 API 도달 실패 — 관리자 설정 조회·갱신은 503으로 드러낸다(공개 runtime-config는 서비스가 기본값으로 강등)
        Map.entry(TradingPolicyUnavailableException.class,          new Mapping(HttpStatus.SERVICE_UNAVAILABLE,    "Trading Core Unavailable", ErrorCode.TRADING_CORE_UNAVAILABLE)),
        // Account.DuplicateAccountException/ManualTradingException/OrderCancelException/
        // PrivacyTradeConflictException(trading-core 소유 원본)은 TradingExceptionHandler로 이관됨 —
        // 아래는 admin이 PrivacyQueryHttpAdapter(내부 API 409 응답 복원)에서 던지는 own-type만 남는다
        Map.entry(AdminPrivacyTradeConflictException.class,            new Mapping(HttpStatus.CONFLICT,           "Conflict")),
        Map.entry(FinanceBudget.OverlappingPeriodException.class,      new Mapping(HttpStatus.CONFLICT,           "Conflict")),
        Map.entry(FinanceAccount.DuplicateAccountNoException.class,    new Mapping(HttpStatus.CONFLICT,           "Conflict")),
        Map.entry(FinanceAccount.LinkedAssetSnapshotsException.class,  new Mapping(HttpStatus.CONFLICT,           "Conflict")),
        Map.entry(FinanceCategory.DuplicateNameException.class,        new Mapping(HttpStatus.CONFLICT,           "Conflict")),
        Map.entry(FinanceGroupInvitation.InvalidInvitationStateException.class, new Mapping(HttpStatus.CONFLICT,  "Conflict")),
        Map.entry(MonthlyClosing.MonthClosedException.class,           new Mapping(HttpStatus.CONFLICT,           "Conflict", ErrorCode.MONTH_CLOSED))
    ));

    // Retry-After 헤더 포함 — 단순 ProblemDetail 반환 불가, 개별 유지
    @ExceptionHandler(User.CooldownException.class)
    public ResponseEntity<ProblemDetail> handleCooldown(User.CooldownException ex) {
        // Retry-After 헤더에 재신청 가능 시각(Unix epoch 초) 포함
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "Cooldown Active", ex.getMessage(), ErrorCode.COOLDOWN_ACTIVE);
        detail.setProperty("retryAfter", ex.getRetryAfter().toString());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().getEpochSecond()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(detail);
    }

    // 필드 오류 메시지 집계 — 공용 유틸 사용
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", ProblemDetailMappings.validationMessage(ex));
    }

    // SSE 타임아웃·연결 종료는 이미 끝난 스트림에 별도 응답 본문을 쓰지 않고 종료 처리
    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void handleAsyncLifecycle(Exception ex, HttpServletResponse response) {
        log.debug("SSE async request 종료: {}", ex.getClass().getSimpleName());
        // 커밋 이전 스트림(예: 최초 emitter.send 이전 종료)은 본문 없이 상태코드만 세팅해 클라이언트에 실패를 알림
        // 이미 커밋된 스트림에 body를 쓰면 HttpMessageNotWritableException이 발생하므로 그 경우는 status 변경도 생략
        if (!response.isCommitted()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        // 실제 서블릿 컨테이너는 커밋 후 setContentType을 무시하므로(스펙상 no-op) 운영 동작엔 영향 없음.
        // MockHttpServletResponse는 커밋 후에도 값을 반영해, 이 호출이 없으면 SseAsyncExceptionHandlingTest가
        // "핸들러가 본문을 쓰지 않으면 Content-Type을 초기화"하는 프레임워크 동작 때문에 거짓 실패한다.
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    // ── 5xx — 서버 오류, 이벤트로 오류 로그 보고 ────────────────────────────────
    // KisApiException/TossApiException(trading-core 소유) 핸들러는 TradingExceptionHandler로 이관됐다 —
    // trading-core의 app_error_logs 저장은 Redis Stream(stream:app.error → admin AppErrorStreamConsumer)으로 전달된다

    // catch-all — MAPPINGS 테이블 우선 조회, 매핑 있으면 4xx 응답(오류 보고 없음) / 없으면 reportUnmapped 후 500 (공통 본문은 ProblemDetailMappings.catchAll)
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAll(Exception ex) {
        return ProblemDetailMappings.catchAll(ex, MAPPINGS, this::reportUnmapped);
    }

    // 매핑 없는 미처리 예외 — 오류 보고 이벤트 + log.error
    private void reportUnmapped(Exception ex) {
        reportErrorLog(ex);
        log.error("미처리 예외 발생: {}", ex.getMessage(), ex);
    }

    // app_error_logs 저장은 admin 리스너가 이벤트로 수행 — 발행 실패(리스너 예외)가 원래 응답을 막지 않도록 격리
    // (TradingExceptionHandler.reportErrorLog와 동일)
    private void reportErrorLog(Exception ex) {
        try {
            eventPublisher.publishEvent(AppErrorRaisedEvent.of(ex, "GlobalExceptionHandler"));
        } catch (Exception reportEx) {
            log.warn("오류 보고 이벤트 발행 실패: {}", reportEx.getMessage());
        }
    }
}
