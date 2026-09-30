package com.kista.tradingweb;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerApiException;
import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.broker.domain.model.BrokerRateLimitException;
import com.kista.platform.web.ProblemDetailMappings;
import com.kista.platform.web.ProblemDetailMappings.Mapping;
import com.kista.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.OrderCancelException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

import static com.kista.platform.web.ProblemDetailMappings.problem;

// trading-core 프로세스 전역 예외→HTTP 매핑 advice(앱셸 tradingweb 소속 — root com.kista.web.GlobalExceptionHandler와 대칭).
// 이 프로세스엔 root advice가 없으므로 컨트롤러 패키지 제약 없이 모든 컨트롤러의 예외를 처리한다:
// trading-core 고유 6종 + BrokerApiException(503) + 범용 JDK/Spring 예외(ProblemDetailMappings.GENERIC) + catch-all 500.
//
// BrokerApiException(KIS/Toss 예외의 벤더 중립 상위 타입, trading-core 소유)은 app_error_logs가 root 소유 테이블이라
// AppErrorRaisedEvent(sharedkernel)를 발행하고 AppErrorStreamPublisher가 Redis Stream(stream:app.error)으로 root에 push한다.
// trading-core는 root를 호출하지 않는다(프로세스 간 단방향 root→trading-core).
//
// ManualTradingException이 BrokerApiException을 cause로 담는 경로는 이 클래스가 에러 로그를 남기지 않는다 —
// ManualTradingService가 던지기 전에 TradingErrorEvent를 발행하고 TradingAlertNotifier → TradingNotifyAdapter.notifyError가
// AppErrorRaisedEvent를 함께 발행해 같은 스트림으로 저장되므로 예외 처리기와 무관한 별도 경로로 이미 보장된다.
//
// @Order(HIGHEST_PRECEDENCE): catch-all(@ExceptionHandler(Exception.class))을 가진 다른 advice가 같은 컨텍스트에 생겨도
// 이 advice가 먼저 평가되도록 하는 방어적 명시 — 지우면 advice 등록 순서에 따라 6종 전용 매핑이 500으로 회귀할 수 있다.
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class TradingExceptionHandler {

    // AppErrorRaisedEvent 발행용 — 저장은 AppErrorStreamPublisher(Redis Stream) → root admin이 담당
    private final ApplicationEventPublisher eventPublisher;

    // trading-core 고유 예외 6종 매핑
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
        Mapping m = ProblemDetailMappings.resolve(ex, MAPPINGS);
        if (m == null) {
            // 위 6종(또는 그 서브클래스)만 이 메서드로 라우팅되므로 도달 불가 — 방어적 가드
            throw new IllegalStateException("매핑 없는 예외가 TradingExceptionHandler로 라우팅됨: " + ex.getClass());
        }
        return problem(m.status(), m.title(), ex.getMessage());
    }

    // KIS·Toss 등 모든 증권사 API 실패를 벤더 중립 BrokerApiException 하나로 503 매핑 — title은 벤더 표기로 도출("KIS API Error"/"Toss API Error", 저장은 이벤트로 위임)
    @ExceptionHandler(BrokerApiException.class)
    public ProblemDetail handleBrokerApiException(BrokerApiException ex) {
        reportErrorLog(ex);
        log.error("{} API 오류: {}", ex.vendorLabel(), ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.vendorLabel() + " API Error", ex.getMessage());
    }

    // 필드 오류 메시지 집계 — 공용 유틸 사용
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", ProblemDetailMappings.validationMessage(ex));
    }

    // catch-all — 범용 매핑(GENERIC) 우선 조회, 매핑 있으면 4xx(에러 로그 없음) / 없으면 500 + 이벤트로 에러 로그 보고.
    // isClientDisconnect 가드: kista-ui가 직접 호출하는 브라우저·모바일 라우트가 섞여 있어 클라이언트 중도 이탈(broken pipe)이
    // app_error_logs를 오염시키는 결함을 막는다
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        return ProblemDetailMappings.catchAll(ex, ProblemDetailMappings.GENERIC, this::reportUnmapped);
    }

    // 매핑 없는 미처리 예외 — 오류 보고 이벤트 + log.error
    private void reportUnmapped(Exception ex) {
        reportErrorLog(ex);
        log.error("미처리 예외 발생: {}", ex.getMessage(), ex);
    }

    // app_error_logs가 root 소유라 이벤트로 보고 — 발행 실패(리스너 예외)가 원래 응답을 막지 않도록 격리
    // (스택트레이스 전체를 보내고 30줄 truncate는 저장 측(root)에서 수행)
    private void reportErrorLog(Exception ex) {
        try {
            eventPublisher.publishEvent(AppErrorRaisedEvent.of(ex, "TradingExceptionHandler"));
        } catch (Exception reportEx) {
            log.warn("오류 보고 이벤트 발행 실패: {}", reportEx.getMessage());
        }
    }
}
