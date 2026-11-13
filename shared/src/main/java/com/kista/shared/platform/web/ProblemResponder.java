package com.kista.shared.platform.web;

import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

// 예외 1종을 응답으로 바꾸는 함수 — 표(Mapping)로 못 나타내는 응답(헤더·동적 title·오류 보고)을 모듈이 직접 만든다
@FunctionalInterface
public interface ProblemResponder {

    ResponseEntity<ProblemDetail> respond(Exception ex, ExceptionReporter reporter);

    // 표 매핑 → 응답 함수(오류 보고 없음)
    static ProblemResponder of(Mapping mapping) {
        return (ex, reporter) -> ResponseEntity.status(mapping.status()).body(ProblemDetailMappings.toProblem(mapping, ex));
    }
}
