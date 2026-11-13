package com.kista.shared.platform.web;

import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProblemDetailExceptionHandlerTest {

    private final List<Exception> reported = new ArrayList<>(); // 보고된 예외 기록

    // 테스트용 예외 계층 — 상위는 표 매핑, 하위는 응답 함수
    private static class ParentException extends RuntimeException {
        ParentException(String message) { super(message); }
    }

    private static class ChildException extends ParentException {
        ChildException(String message) { super(message); }
    }

    // 표·응답 함수를 그대로 내놓는 테스트 기여
    private record TestContributor(Map<Class<? extends Exception>, Mapping> mappings,
                                   Map<Class<? extends Exception>, ProblemResponder> responders) implements ProblemDetailMappingContributor {
    }

    private ProblemDetailExceptionHandler handler(ProblemDetailMappingContributor... contributors) {
        return handler((ExceptionReporter) reported::add, contributors);
    }

    private static ProblemDetailExceptionHandler handler(ExceptionReporter reporter, ProblemDetailMappingContributor... contributors) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("reporter", reporter);
        ObjectProvider<ExceptionReporter> provider = beanFactory.getBeanProvider(ExceptionReporter.class);
        return new ProblemDetailExceptionHandler(List.of(contributors), provider);
    }

    private ProblemDetail handle(Exception ex) {
        return handler().handleAll(ex).getBody();
    }

    @Test
    void duplicate_contribution_fails_startup() {
        var a = new TestContributor(Map.of(ParentException.class, new Mapping(HttpStatus.CONFLICT, "Conflict")), Map.of());
        var b = new TestContributor(Map.of(), Map.of(ParentException.class, (ex, r) -> ResponseEntity.ok().build()));

        assertThatThrownBy(() -> handler(a, b)).isInstanceOf(IllegalStateException.class).hasMessageContaining(ParentException.class.getName());
    }

    @Test
    void contribution_overlapping_generic_fails_startup() {
        var c = new TestContributor(Map.of(IllegalStateException.class, new Mapping(HttpStatus.CONFLICT, "Conflict")), Map.of());

        assertThatThrownBy(() -> handler(c)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nearest_class_wins_across_mappings_and_responders() {
        var c = new TestContributor(
                Map.of(ParentException.class, new Mapping(HttpStatus.CONFLICT, "Conflict")),
                Map.of(ChildException.class, (ex, r) -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build()));
        var h = handler(c);

        assertThat(h.handleAll(new ChildException("x")).getStatusCode().value()).isEqualTo(429);
        assertThat(h.handleAll(new ParentException("x")).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void responder_report_failure_does_not_break_response() {
        var c = new TestContributor(Map.of(), Map.of(ParentException.class, (ex, r) -> {
            r.report(ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }));
        ExceptionReporter failing = ex -> { throw new IllegalStateException("저장 실패"); };

        assertThat(handler(failing, c).handleAll(new ParentException("x")).getStatusCode().value()).isEqualTo(503);
        assertThat(handler(failing).handleAll(new RuntimeException("boom")).getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void clientDisconnect_returns503AndSkipsReport() {
        var p = handle(new IOException("Broken pipe"));

        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getTitle()).isEqualTo("Client Disconnected");
        assertThat(p.getDetail()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void mappedExceptionWithDisconnectCause_keepsMappedResponse() {
        // 이탈 메시지를 cause로 감싼 매핑 예외는 이탈(503)이 아니라 매핑 응답을 유지한다
        var p = handle(new IllegalStateException("주문 실패", new IOException("Connection reset by peer")));

        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(reported).isEmpty();
    }

    @Test
    void mappedException_returnsMappedStatusAndSkipsReport() {
        var p = handle(new NoSuchElementException("계좌를 찾을 수 없습니다: 0198f0c2-0000-7000-8000-000000000001"));

        assertThat(p.getStatus()).isEqualTo(404);
        assertThat(p.getTitle()).isEqualTo("Resource Not Found");
        // 원본 메시지의 id(UUID 등)가 응답에 실리지 않도록 고정 문구
        assertThat(p.getDetail()).isEqualTo("요청한 정보를 찾을 수 없습니다.");
        assertThat(reported).isEmpty();
    }

    @Test
    void unmappedException_reportsAndReturns500() {
        var ex = new UnsupportedOperationException("boom");

        var p = handle(ex);

        assertThat(p.getStatus()).isEqualTo(500);
        assertThat(p.getTitle()).isEqualTo("Internal Server Error");
        assertThat(p.getDetail()).isEqualTo("예기치 않은 오류가 발생했습니다.");
        assertThat(reported).containsExactly(ex);
    }

    @Test
    void mappedWithoutCode_hasNoCodeProperty() {
        var p = handle(new IllegalArgumentException("잘못된 값입니다."));
        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(p.getProperties() == null || !p.getProperties().containsKey("code")).isTrue();
    }

    @Test
    void securityException_hasAccessDeniedCode() {
        var p = handle(new SecurityException("접근 권한이 없습니다."));
        assertThat(p.getStatus()).isEqualTo(403);
        assertThat(p.getDetail()).isEqualTo("접근 권한이 없습니다.");
        assertThat(p.getProperties()).containsEntry("code", "ACCESS_DENIED");
    }

    @Test
    void frameworkExceptions_useFixedKoreanDetails() {
        // 생성자 시그니처가 버전마다 달라 mock 서브클래스로 생성 — resolve()가 클래스 계층을 따라 올라가 GENERIC 매핑을 찾는다
        Map<Exception, String> expected = Map.of(
                mock(MissingServletRequestParameterException.class), "필수 요청 값이 누락되었습니다.",
                mock(MethodArgumentTypeMismatchException.class),     "요청 값의 형식이 올바르지 않습니다.",
                mock(HttpMessageNotReadableException.class),         "요청 형식이 올바르지 않습니다.",
                mock(NoResourceFoundException.class),                "요청한 경로를 찾을 수 없습니다.",
                new java.time.format.DateTimeParseException("Text 'abc' could not be parsed", "abc", 0), "날짜 형식이 올바르지 않습니다.");
        expected.forEach((ex, detail) -> {
            var p = handle(ex);
            assertThat(p.getDetail()).as(ex.getClass().getSimpleName()).isEqualTo(detail);
            assertThat(p.getStatus()).isBetween(400, 404);
        });
        assertThat(reported).isEmpty();
    }

    @Test
    void unsupported_http_method_maps_to_405_not_500() {
        var p = handle(new HttpRequestMethodNotSupportedException("GET"));
        assertThat(p.getStatus()).isEqualTo(405);
        assertThat(p.getDetail()).isEqualTo("지원하지 않는 요청 방식입니다.");
        assertThat(reported).isEmpty();
    }
}
