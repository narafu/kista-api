package com.kista.shared.platform.web;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.kista.shared.platform.web.ProblemDetailMappings.problem;

// 프로세스당 하나뿐인 예외→HTTP advice(root·trading-core 공용) — 예외별 응답은 모듈의 ProblemDetailMappingContributor 빈이 기여하고
// 여기서 GENERIC과 합쳐 한 표로 만든다. advice가 둘이면 catch-all을 가진 쪽이 모든 예외를 먼저 잡아 다른 쪽 매핑이 죽는다.
// catch-all 순서: 표(클래스 계층 탐색) → 클라이언트 이탈(기록 없이 503) → 미매핑 500 + 오류 보고.
// 표가 이탈 판정보다 먼저인 이유: 이탈 판정은 cause 체인 메시지 기준이라 connection reset을 감싼 도메인 예외가 고정 문구 응답을 잃지 않게 한다
@Slf4j
@RestControllerAdvice
public class ProblemDetailExceptionHandler {

    private static final ExceptionReporter NO_REPORTER = ex -> { }; // 보고 빈이 없는 컨텍스트(@WebMvcTest 슬라이스)용 — 운영 컨텍스트에는 항상 있다(컨텍스트 로드 테스트가 단언)

    private final Map<Class<? extends Exception>, ProblemResponder> responders; // GENERIC + 모듈 기여 합집합
    private final ObjectProvider<ExceptionReporter> reporter;                    // 프로세스별 오류 보고

    public ProblemDetailExceptionHandler(List<ProblemDetailMappingContributor> contributors, ObjectProvider<ExceptionReporter> reporter) {
        this.responders = merge(contributors);
        this.reporter = reporter;
    }

    // GENERIC + 기여 합치기 — 같은 예외가 두 번 나오면 기동 실패(덮어쓰면 어느 매핑이 이겼는지 드러나지 않는다)
    static Map<Class<? extends Exception>, ProblemResponder> merge(List<ProblemDetailMappingContributor> contributors) {
        Map<Class<? extends Exception>, ProblemResponder> merged = new HashMap<>();
        ProblemDetailMappings.GENERIC.forEach((type, mapping) -> put(merged, type, ProblemResponder.of(mapping)));
        for (ProblemDetailMappingContributor contributor : contributors) {
            contributor.mappings().forEach((type, mapping) -> put(merged, type, ProblemResponder.of(mapping)));
            contributor.responders().forEach((type, responder) -> put(merged, type, responder));
        }
        return Map.copyOf(merged);
    }

    private static void put(Map<Class<? extends Exception>, ProblemResponder> merged, Class<? extends Exception> type, ProblemResponder responder) {
        if (merged.putIfAbsent(type, responder) != null) {
            throw new IllegalStateException(type.getName() + " 예외의 응답 매핑이 두 곳에 있습니다.");
        }
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

    // catch-all — 표 → 클라이언트 이탈 → 미매핑 500 순
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAll(Exception ex) {
        ProblemResponder responder = ProblemDetailMappings.resolve(ex, responders);
        if (responder != null) {
            return responder.respond(ex, this::report);
        }
        // 클라이언트가 응답 수신 전 연결을 끊으면 직렬화·응답 쓰기가 실패해 catch-all로 떨어진다 — 기록 없이 종료
        if (ProblemDetailMappings.isClientDisconnect(ex)) {
            log.debug("클라이언트 연결 끊김으로 응답 미완: {}", ex.getMessage());
            return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, "Client Disconnected", ""));
        }
        // 매핑 없는 미처리 예외 — 오류 보고 + log.error 후 500
        report(ex);
        log.error("미처리 예외 발생: {}", ex.getMessage(), ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "예기치 않은 오류가 발생했습니다."));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail detail) {
        return ResponseEntity.status(detail.getStatus()).body(detail);
    }

    // 보고 실패(리스너 예외 등)가 원래 응답을 막지 않도록 격리
    private void report(Exception ex) {
        try {
            reporter.getIfAvailable(() -> NO_REPORTER).report(ex);
        } catch (Exception reportEx) {
            log.warn("오류 보고 실패: {}", reportEx.getMessage());
        }
    }
}
