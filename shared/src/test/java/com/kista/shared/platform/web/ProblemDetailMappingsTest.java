package com.kista.shared.platform.web;

import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

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
    void problem_withNullCode_hasNoCodeProperty() {
        var p = ProblemDetailMappings.problem(HttpStatus.CONFLICT, "Conflict", "충돌입니다.", null);
        assertThat(p.getProperties() == null || !p.getProperties().containsKey("code")).isTrue();
    }
}
