package com.kista.platform.web;

import com.kista.platform.web.ProblemDetailMappings.Mapping;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProblemDetailMappingsTest {

    // 테스트용 서브클래스 — 상위 클래스 매핑 탐색 검증
    private static class CustomIllegalArgument extends IllegalArgumentException {
        CustomIllegalArgument(String message) { super(message); }
    }

    @Test
    void resolve_walksSuperclassHierarchy() {
        Mapping m = ProblemDetailMappings.resolve(new CustomIllegalArgument("x"), ProblemDetailMappings.GENERIC);

        assertThat(m).isEqualTo(new Mapping(HttpStatus.BAD_REQUEST, "Invalid Request"));
    }

    @Test
    void resolve_returnsNullWhenNoMapping() {
        assertThat(ProblemDetailMappings.resolve(new UnsupportedOperationException(), ProblemDetailMappings.GENERIC)).isNull();
    }

    @Test
    void withGeneric_specificOverridesGenericOnDuplicateKey() {
        Mapping override = new Mapping(HttpStatus.CONFLICT, "Conflict");

        var merged = ProblemDetailMappings.withGeneric(Map.of(IllegalStateException.class, override));

        assertThat(ProblemDetailMappings.resolve(new IllegalStateException(), merged)).isEqualTo(override);
        // 겹치지 않는 범용 매핑은 유지
        assertThat(ProblemDetailMappings.resolve(new SecurityException(), merged).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void isClientDisconnect_brokenPipeMessage() {
        assertThat(ProblemDetailMappings.isClientDisconnect(new IOException("Broken pipe"))).isTrue();
    }

    @Test
    void isClientDisconnect_detectsNestedCause() {
        assertThat(ProblemDetailMappings.isClientDisconnect(new RuntimeException("wrap", new IOException("Connection reset by peer")))).isTrue();
    }

    @Test
    void isClientDisconnect_unrelatedExceptionIsFalse() {
        assertThat(ProblemDetailMappings.isClientDisconnect(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    void problem_setsStatusTitleAndDetail() {
        var p = ProblemDetailMappings.problem(HttpStatus.NOT_FOUND, "Not Found", "없음");

        assertThat(p.getStatus()).isEqualTo(404);
        assertThat(p.getTitle()).isEqualTo("Not Found");
        assertThat(p.getDetail()).isEqualTo("없음");
    }

    @Test
    void catchAll_clientDisconnect_returns503AndSkipsReport() {
        var reported = new java.util.ArrayList<Exception>();

        var p = ProblemDetailMappings.catchAll(new IOException("Broken pipe"), ProblemDetailMappings.GENERIC, reported::add);

        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getTitle()).isEqualTo("Client Disconnected");
        assertThat(p.getDetail()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void catchAll_mappedException_returnsMappedStatusAndSkipsReport() {
        var reported = new java.util.ArrayList<Exception>();

        var p = ProblemDetailMappings.catchAll(new NoSuchElementException("없음"), ProblemDetailMappings.GENERIC, reported::add);

        assertThat(p.getStatus()).isEqualTo(404);
        assertThat(p.getTitle()).isEqualTo("Resource Not Found");
        assertThat(p.getDetail()).isEqualTo("없음");
        assertThat(reported).isEmpty();
    }

    @Test
    void catchAll_unmappedException_invokesConsumerAndReturns500() {
        var reported = new java.util.ArrayList<Exception>();
        var ex = new UnsupportedOperationException("boom");

        var p = ProblemDetailMappings.catchAll(ex, ProblemDetailMappings.GENERIC, reported::add);

        assertThat(p.getStatus()).isEqualTo(500);
        assertThat(p.getTitle()).isEqualTo("Internal Server Error");
        assertThat(p.getDetail()).isEqualTo("예기치 않은 오류가 발생했습니다.");
        assertThat(reported).containsExactly(ex);
    }

    @Test
    void toProblem_withCode_setsCodeProperty() {
        var p = ProblemDetailMappings.toProblem(
                new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.MONTH_CLOSED), new IllegalStateException("마감된 달입니다."));
        assertThat(p.getStatus()).isEqualTo(409);
        assertThat(p.getDetail()).isEqualTo("마감된 달입니다.");
        assertThat(p.getProperties()).containsEntry("code", "MONTH_CLOSED");
    }

    @Test
    void toProblem_fixedDetail_overridesExceptionMessage() {
        var p = ProblemDetailMappings.toProblem(
                new Mapping(HttpStatus.BAD_REQUEST, "Bad Request", null, "요청 형식이 올바르지 않습니다."),
                new IllegalArgumentException("Failed to convert value of type 'java.lang.String'"));
        assertThat(p.getDetail()).isEqualTo("요청 형식이 올바르지 않습니다.");
    }

    @Test
    void catchAll_mappedWithoutCode_hasNoCodeProperty() {
        var p = ProblemDetailMappings.catchAll(new IllegalArgumentException("잘못된 값입니다."),
                ProblemDetailMappings.GENERIC, ex -> {});
        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(p.getProperties() == null || !p.getProperties().containsKey("code")).isTrue();
    }

    @Test
    void catchAll_securityException_hasAccessDeniedCode() {
        var p = ProblemDetailMappings.catchAll(new SecurityException("접근 권한이 없습니다."),
                ProblemDetailMappings.GENERIC, ex -> {});
        assertThat(p.getStatus()).isEqualTo(403);
        assertThat(p.getDetail()).isEqualTo("접근 권한이 없습니다.");
        assertThat(p.getProperties()).containsEntry("code", "ACCESS_DENIED");
    }

    @Test
    void catchAll_frameworkException_usesFixedKoreanDetail() {
        var p = ProblemDetailMappings.catchAll(
                new java.time.format.DateTimeParseException("Text 'abc' could not be parsed", "abc", 0),
                ProblemDetailMappings.GENERIC, ex -> {});
        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(p.getDetail()).isEqualTo("날짜 형식이 올바르지 않습니다.");
    }

    @Test
    void problem_withNullCode_hasNoCodeProperty() {
        var p = ProblemDetailMappings.problem(HttpStatus.CONFLICT, "Conflict", "충돌입니다.", null);
        assertThat(p.getProperties() == null || !p.getProperties().containsKey("code")).isTrue();
    }

    @Test
    void catchAll_frameworkExceptions_useFixedKoreanDetails() {
        // 생성자 시그니처가 버전마다 달라 mock 서브클래스로 생성 — resolve()가 클래스 계층을 따라 올라가 GENERIC 매핑을 찾는다
        Map<Exception, String> expected = Map.of(
                mock(MissingServletRequestParameterException.class), "필수 요청 값이 누락되었습니다.",
                mock(MethodArgumentTypeMismatchException.class),     "요청 값의 형식이 올바르지 않습니다.",
                mock(HttpMessageNotReadableException.class),         "요청 형식이 올바르지 않습니다.",
                mock(NoResourceFoundException.class),                "요청한 경로를 찾을 수 없습니다.");
        expected.forEach((ex, detail) -> {
            var p = ProblemDetailMappings.catchAll(ex, ProblemDetailMappings.GENERIC, e -> {});
            assertThat(p.getDetail()).as(ex.getClass().getSimpleName()).isEqualTo(detail);
            assertThat(p.getStatus()).isBetween(400, 404);
        });
    }
}
