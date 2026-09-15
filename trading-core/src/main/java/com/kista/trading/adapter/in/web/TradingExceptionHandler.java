package com.kista.trading.adapter.in.web;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.broker.domain.model.BrokerRateLimitException;
import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.OrderCancelException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.NoSuchElementException;

// trading-core 네이티브 컨트롤러(TradingCycleController/OrderCancelController/FidaOrderController/
// AccountController/TradingInternalQueryController/CandleInternalController/
// MarketCalendarInternalController/StrategyCapabilityInternalController 등, 아래 basePackages 7개) 전용
// 예외 매핑.
//
// 과거(4a 패키징 분리 이전)엔 이 클래스가 trading-core 고유 6종만 처리하고, SecurityException/
// NoSuchElementException/IllegalArgumentException 등 나머지는 root com.kista.web.GlobalExceptionHandler가
// basePackages 제약 없이(fallthrough) 그대로 처리한다는 전제였다 — trading-core에 자체
// @SpringBootApplication이 없어 모든 테스트·운영 컨텍스트가 root KistaApplication 하나만 띄웠기
// 때문에 두 advice가 항상 같은 컨텍스트에 공존했다. TradingApplication(:trading-core 자체 부트
// 진입점) 신설로 trading-core가 완전히 독립된 프로세스가 되면서 이 전제가 깨졌다 — 운영에서
// trading-core는 root의 GlobalExceptionHandler를 아예 클래스패스에 갖지 않으므로, 매핑 없는
// 예외가 Spring 기본 에러 응답(ProblemDetail 형식 아님)으로 새는 실제 결함이었다. 아래 GENERIC_MAPPINGS +
// handleGeneric가 root GlobalExceptionHandler의 관련 매핑을 그대로 복제해 이 갭을 메운다(root
// admin/finance/user 전용 own-type 예외는 trading-core에 해당 없어 제외 — 순수 JDK/Spring 프레임워크
// 예외만 대상).
//
// KisApiException/TossApiException(둘 다 trading-core 소유)은 이 클래스가 직접 처리하되, app_error_logs가
// root 소유 테이블이라 POST /api/internal/errors(ErrorLogInternalController, root)를 호출해 저장을 위임한다 —
// 이 계획의 다른 내부 API는 전부 root->trading-core였으나 이 건은 방향이 반대다.
//
// ManualTradingService.java:118/135가 KisApiException/TossApiException을 cause로 담은
// ManualTradingException을 던지는 경로에서, root GlobalExceptionHandler.handleAll이 하던
// "4xx라도 cause가 브로커 API 실패면 saveErrorLog" 동작은 이 클래스에 의도적으로 복제하지 않았다 —
// :118은 priceFetcher가 내부에서 절대 예외를 던지지 않아 도달 불가(주석 확인), :135는 catch 블록에서
// eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()))를 먼저 호출하는데
// 이 이벤트를 구독하는 TradingAlertNotifier.onTradingError가 userId==null이면
// notifyPort.notifyError(...)를 호출하고, 이는 com.kista.admin.adapter.out.aop.ErrorLogAspect가
// AOP로 가로채 appErrorLogPort.save(...)를 실행한다 — 즉 app_error_logs 저장은 이미
// 예외 처리기와 무관한 별도 경로로 보장되어 있어 여기서 재현할 필요가 없다.
//
// @Order(HIGHEST_PRECEDENCE)는 방어적 명시가 아니라 실질적 불변식이다: root
// GlobalExceptionHandler.handleAll은 @ExceptionHandler(Exception.class) catch-all이라 모든
// 예외 타입에 매치되므로, root advice가 이 클래스보다 먼저 평가되면 이 클래스는 절대 호출되지 않고
// 여기서 다루는 6종은 (root MAPPINGS에서 이미 삭제됐으므로) 전부 500으로 회귀한다. 현재 root가
// @Order를 선언하지 않아(기본값 LOWEST_PRECEDENCE) 우연히 안전할 뿐 — 이 @Order를 지우면
// advice 등록 순서에 따라 언제든 깨질 수 있다.
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {
        "com.kista.trading.adapter.in.web",
        "com.kista.account.adapter.in.web",
        "com.kista.privacy.adapter.in.web",
        "com.kista.trading.stats.adapter.in.web",
        "com.kista.broker.adapter.in.web",
        "com.kista.marketcalendar.adapter.in.web",
        "com.kista.matching.adapter.in.web"
})
@RequiredArgsConstructor
public class TradingExceptionHandler {

    // root ErrorLogInternalController(/api/internal/errors) 호출용 — 순수 로그 저장이라 공용 짧은 타임아웃 빈 재사용.
    // 이 클래스는 @RestControllerAdvice라 basePackages와 무관하게 모든 @WebMvcTest 슬라이스에서
    // 빈으로 생성된다 — RestClient를 직접 주입하면 두 모듈의 웹 슬라이스 테스트 전체가 빈 미제공으로
    // 컨텍스트 로드에 실패한다. ObjectProvider로 늦춰 받으면 빈이 없어도(테스트 슬라이스 등) 생성 자체는
    // 성공하고, 실제 호출 시점(getIfAvailable)에만 없으면 null로 스킵한다 — 런타임에는
    // InternalApiClientConfig(:shared)의 빈이 정상 해석된다
    private final ObjectProvider<RestClient> internalApiRestClient;

    // status·title 쌍 튜플 — 테이블 값 타입 (root GlobalExceptionHandler와 동일 패턴)
    private record Mapping(HttpStatus status, String title) {}

    // root ErrorLogInternalController의 ErrorLogRequest와 JSON 필드가 구조적으로 일치하는 own-type —
    // 컴파일 경계상 root DTO를 직접 import할 수 없어 JSON 계약만 맞춰 별도 선언(다른 내부 API의
    // 요청/응답 own-type 이중복제와 동일 패턴)
    private record ErrorLogRequest(String errorType, String message, String stackTrace, Map<String, String> context) {}

    private static final Map<Class<? extends Exception>, Mapping> MAPPINGS = Map.of(
            BrokerCredentialException.class,        new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Broker Credentials"),
            BrokerRateLimitException.class,          new Mapping(HttpStatus.TOO_MANY_REQUESTS,     "KIS Rate Limit"),
            ManualTradingException.class,            new Mapping(HttpStatus.CONFLICT,              "Conflict"),
            OrderCancelException.class,              new Mapping(HttpStatus.CONFLICT,              "Conflict"),
            PrivacyTradeConflictException.class,     new Mapping(HttpStatus.CONFLICT,              "Conflict"),
            Account.DuplicateAccountException.class, new Mapping(HttpStatus.CONFLICT,              "Conflict")
    );

    @ExceptionHandler({
            BrokerCredentialException.class, BrokerRateLimitException.class,
            ManualTradingException.class, OrderCancelException.class,
            PrivacyTradeConflictException.class, Account.DuplicateAccountException.class
    })
    public ProblemDetail handleTradingCoreExceptions(Exception ex) {
        Mapping m = resolveMapping(ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(m.status(), ex.getMessage());
        detail.setTitle(m.title());
        return detail;
    }

    // root GlobalExceptionHandler.handleKisApiException과 동일 매핑(503) — 저장만 내부 API로 위임
    @ExceptionHandler(KisApiException.class)
    public ProblemDetail handleKisApiException(KisApiException ex) {
        reportErrorLog(ex);
        log.error("KIS API 오류: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "KIS API Error", ex.getMessage());
    }

    // root GlobalExceptionHandler.handleTossApiException과 동일 매핑(503) — 저장만 내부 API로 위임
    @ExceptionHandler(TossApiException.class)
    public ProblemDetail handleTossApiException(TossApiException ex) {
        reportErrorLog(ex);
        log.error("Toss API 오류: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Toss API Error", ex.getMessage());
    }

    // root GlobalExceptionHandler.MAPPINGS 중 trading-core에도 실제로 던져질 수 있는 순수 JDK/Spring
    // 프레임워크 예외만 복제 — admin/finance/user 소유 own-type 예외는 trading-core에 해당 없어 제외
    private static final Map<Class<? extends Exception>, Mapping> GENERIC_MAPPINGS = Map.of(
            SecurityException.class,                       new Mapping(HttpStatus.FORBIDDEN,   "Access Denied"),
            IllegalStateException.class,                    new Mapping(HttpStatus.BAD_REQUEST, "Invalid State"),
            NoSuchElementException.class,                   new Mapping(HttpStatus.NOT_FOUND,   "Resource Not Found"),
            IllegalArgumentException.class,                 new Mapping(HttpStatus.BAD_REQUEST, "Invalid Request"),
            MissingServletRequestParameterException.class,  new Mapping(HttpStatus.BAD_REQUEST, "Bad Request"),
            MethodArgumentTypeMismatchException.class,      new Mapping(HttpStatus.BAD_REQUEST, "Bad Request"),
            DateTimeParseException.class,                   new Mapping(HttpStatus.BAD_REQUEST, "Invalid Date Format"),
            HttpMessageNotReadableException.class,          new Mapping(HttpStatus.BAD_REQUEST, "Malformed Request"),
            NoResourceFoundException.class,                 new Mapping(HttpStatus.NOT_FOUND,   "Not Found")
    );

    // root GlobalExceptionHandler.handleValidation과 동일 — 필드 오류 메시지 집계는 단순 테이블 조회로 불가해 개별 유지
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList()
                .toString();
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", message);
    }

    // root GlobalExceptionHandler.handleAll과 동일 구조의 catch-all — GENERIC_MAPPINGS 우선 조회,
    // 매핑 있으면 4xx(에러 로그 없음) / 없으면 500 + 내부 API로 에러 로그 저장(reportErrorLog 재사용).
    // isClientDisconnect 가드는 root GlobalExceptionHandler.handleAll에서 그대로 이식 — AccountController/
    // TradingCycleController 등 이 basePackages 안에는 kista-ui가 직접 호출하는 브라우저·모바일 라우트가
    // 섞여있어(내부 전용 API만 있는 게 아님), 클라이언트 중도 이탈(broken pipe)이 app_error_logs를
    // 오염시키던 root의 과거 결함(200ae4cb)이 여기서도 그대로 재현될 수 있다
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        if (isClientDisconnect(ex)) {
            log.debug("클라이언트 연결 끊김으로 응답 미완: {}", ex.getMessage());
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Client Disconnected", "");
        }
        Mapping m = resolveGenericMapping(ex);
        if (m != null) {
            return problem(m.status(), m.title(), ex.getMessage());
        }
        reportErrorLog(ex);
        log.error("미처리 예외 발생: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "예기치 않은 오류가 발생했습니다");
    }

    // 원인 체인에 Tomcat ClientAbortException 또는 broken pipe/connection reset 메시지가 있으면 클라이언트 이탈로 판정
    // (root GlobalExceptionHandler.isClientDisconnect와 동일 로직 — own-type 복제가 아니라 두 advice 각자
    // 자기 basePackages 범위에서 독립 판정해야 해 공유 유틸로 뺄 이유가 없다)
    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getName().equals("org.apache.catalina.connector.ClientAbortException")) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer"))) return true;
        }
        return false;
    }

    // 클래스 계층 탐색 — resolveMapping(6종 전용)과 별개로 GENERIC_MAPPINGS를 조회, 매핑 없으면 null(500 폴백)
    private static Mapping resolveGenericMapping(Exception ex) {
        Class<?> cls = ex.getClass();
        while (cls != null && Exception.class.isAssignableFrom(cls)) {
            @SuppressWarnings("unchecked")
            Mapping m = GENERIC_MAPPINGS.get((Class<? extends Exception>) cls);
            if (m != null) return m;
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String msg) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, msg);
        detail.setTitle(title);
        return detail;
    }

    // app_error_logs가 root 소유라 내부 API로 저장 위임 — 호출 실패가 원래 응답을 막지 않도록 격리
    // (root AppErrorLogPersistenceAdapter.save(Exception,String)과 동일하게 스택트레이스 전체를 보내고
    // 30줄 truncate는 저장 측(root)에서 수행)
    private void reportErrorLog(Exception ex) {
        try {
            RestClient client = internalApiRestClient.getIfAvailable();
            if (client == null) return; // 내부 API 클라이언트 미구성(웹 슬라이스 테스트 등) — 저장 생략
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            client.post()
                    .uri("/api/internal/errors")
                    .body(new ErrorLogRequest(ex.getClass().getSimpleName(), ex.getMessage(), sw.toString(),
                            Map.of("caller", "TradingExceptionHandler")))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception reportEx) {
            log.warn("오류 로그 저장 실패: {}", reportEx.getMessage());
        }
    }

    // 클래스 계층 탐색 — root GlobalExceptionHandler.resolveMapping과 동일 패턴.
    // @ExceptionHandler는 서브클래스도 매치하므로(assignability 기준) 테이블 조회도 exact-class가
    // 아닌 상위 클래스 탐색이어야 6종 중 하나의 향후 서브클래스가 NPE 없이 매칭된다.
    private static Mapping resolveMapping(Exception ex) {
        Class<?> cls = ex.getClass();
        while (cls != null && Exception.class.isAssignableFrom(cls)) {
            @SuppressWarnings("unchecked")
            Mapping m = MAPPINGS.get((Class<? extends Exception>) cls);
            if (m != null) return m;
            cls = cls.getSuperclass();
        }
        // 이 advice의 basePackages에 매치된 컨트롤러는 위 6종(또는 그 서브클래스)만 이 메서드로
        // 라우팅되므로 도달 불가 — 방어적 null 가드
        throw new IllegalStateException("매핑 없는 예외가 TradingExceptionHandler로 라우팅됨: " + ex.getClass());
    }
}
