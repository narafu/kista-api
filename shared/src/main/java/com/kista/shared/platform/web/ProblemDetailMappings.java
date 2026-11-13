package com.kista.shared.platform.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.NoSuchElementException;

// 공용 advice(ProblemDetailExceptionHandler)와 모듈 기여(ProblemDetailMappingContributor)가 쓰는 예외→ProblemDetail 매핑 유틸 —
// JDK/Spring 범용 예외 테이블·클래스 계층 탐색·클라이언트 이탈 판정·검증 메시지 집계.
@Slf4j
public final class ProblemDetailMappings {

    // status·title·code·고정 detail 튜플 — code null이면 응답에 code 프로퍼티 없음, fixedDetail null이면 예외 메시지를 detail로 사용
    public record Mapping(HttpStatus status, String title, ErrorCode code, String fixedDetail) {
        public Mapping(HttpStatus status, String title) { this(status, title, null, null); }
        public Mapping(HttpStatus status, String title, ErrorCode code) { this(status, title, code, null); }
    }

    public static final String CODE_PROPERTY = "code"; // ProblemDetail 확장 프로퍼티 키 — kista-ui 계약

    // 두 프로세스에 모두 던져질 수 있는 순수 JDK/Spring 프레임워크 예외 매핑 — 도메인 전용 예외는 각 advice가 자기 테이블에 추가한다
    public static final Map<Class<? extends Exception>, Mapping> GENERIC = Map.of(
            SecurityException.class,                       new Mapping(HttpStatus.FORBIDDEN,   "Access Denied", ErrorCode.ACCESS_DENIED),
            IllegalStateException.class,                   new Mapping(HttpStatus.BAD_REQUEST, "Invalid State"),
            NoSuchElementException.class,                  new Mapping(HttpStatus.NOT_FOUND,   "Resource Not Found", null, "요청한 정보를 찾을 수 없습니다."), // 메시지에 id(UUID 등)가 실려 고정 문구 — 원문은 debug 로그
            IllegalArgumentException.class,                new Mapping(HttpStatus.BAD_REQUEST, "Invalid Request"),
            // 아래 프레임워크 예외는 원본 메시지가 영어 내부 정보(타입명·파서 오류)라 고정 한국어 detail로 교체 — 원문은 debug 로그
            MissingServletRequestParameterException.class, new Mapping(HttpStatus.BAD_REQUEST, "Bad Request", null, "필수 요청 값이 누락되었습니다."),
            MethodArgumentTypeMismatchException.class,     new Mapping(HttpStatus.BAD_REQUEST, "Bad Request", null, "요청 값의 형식이 올바르지 않습니다."),
            DateTimeParseException.class,                  new Mapping(HttpStatus.BAD_REQUEST, "Invalid Date Format", null, "날짜 형식이 올바르지 않습니다."),
            // 요청 바디 파싱 실패(잘못된 JSON, enum에 없는 값 등) — 매핑 누락 시 catch-all이 500으로 처리해 클라이언트 오류가 서버 오류로 잘못 보고됨
            HttpMessageNotReadableException.class,         new Mapping(HttpStatus.BAD_REQUEST, "Malformed Request", null, "요청 형식이 올바르지 않습니다."),
            // 존재하지 않는 정적 리소스·경로(취약점 스캐너의 /actuator/** probe 등) — 매핑 없으면 catch-all이 500 + 오류 로그로 처리해 오염
            NoResourceFoundException.class,                new Mapping(HttpStatus.NOT_FOUND,   "Not Found", null, "요청한 경로를 찾을 수 없습니다."),
            // 경로는 있으나 HTTP 메서드 미지원(PUT 전용 경로에 GET 등) — 매핑 없으면 catch-all이 500 + 오류 로그로 처리
            HttpRequestMethodNotSupportedException.class,  new Mapping(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", null, "지원하지 않는 요청 방식입니다.")
    );

    private ProblemDetailMappings() {}

    // 클래스 계층 탐색 — @ExceptionHandler는 서브클래스도 매치하므로(assignability 기준) 테이블 조회도 exact-class가 아닌
    // 상위 클래스 탐색이어야 등록된 예외의 향후 서브클래스가 매칭된다. 매핑 없으면 null — 호출부가 500 폴백 여부를 결정한다
    public static <V> V resolve(Exception ex, Map<Class<? extends Exception>, V> table) {
        Class<?> cls = ex.getClass();
        while (cls != null && Exception.class.isAssignableFrom(cls)) {
            @SuppressWarnings("unchecked")
            V m = table.get((Class<? extends Exception>) cls);
            if (m != null) return m;
            cls = cls.getSuperclass();
        }
        return null;
    }

    // 원인 체인에 Tomcat ClientAbortException 또는 broken pipe/connection reset 메시지가 있으면 클라이언트 이탈로 판정
    // (JsonMappingException도 IOException을 상속하므로 instanceof IOException으로는 실제 직렬화 결함과 구분 불가 — 클래스명·메시지로 좁힘)
    public static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getName().equals("org.apache.catalina.connector.ClientAbortException")) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer"))) return true;
        }
        return false;
    }

    // 매핑 → ProblemDetail — fixedDetail 우선(원본 메시지는 debug 로그), code 있으면 확장 프로퍼티로 싣는다
    public static ProblemDetail toProblem(Mapping m, Exception ex) {
        if (m.fixedDetail() == null) return problem(m.status(), m.title(), ex.getMessage(), m.code());
        log.debug("고정 detail로 대체된 예외 메시지: {}", ex.getMessage());
        return problem(m.status(), m.title(), m.fixedDetail(), m.code());
    }

    // ProblemDetail 생성 헬퍼 — 모든 핸들러에서 반복되는 3줄 보일러플레이트 제거
    public static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    // code 포함 ProblemDetail 생성 — code null이면 프로퍼티를 세팅하지 않아 응답에 키 자체가 없다
    public static ProblemDetail problem(HttpStatus status, String title, String detail, ErrorCode code) {
        ProblemDetail problem = problem(status, title, detail);
        if (code != null) problem.setProperty(CODE_PROPERTY, code.name());
        return problem;
    }

    // 필드 오류 메시지 집계 — "[field: message, ...]" 형식
    public static String validationMessage(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList()
                .toString();
    }
}
