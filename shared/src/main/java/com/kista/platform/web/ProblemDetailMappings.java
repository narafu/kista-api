package com.kista.platform.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

// root GlobalExceptionHandler와 trading-core TradingExceptionHandler가 공유하는 예외→ProblemDetail 매핑 유틸 —
// 두 advice가 각자 복제하던 JDK/Spring 범용 예외 테이블·클래스 계층 탐색·클라이언트 이탈 판정·검증 메시지 집계를 한 벌로 통합한다.
@Slf4j
public final class ProblemDetailMappings {

    // status·title 쌍 튜플 — 매핑 테이블 값 타입
    public record Mapping(HttpStatus status, String title) {}

    // 두 프로세스에 모두 던져질 수 있는 순수 JDK/Spring 프레임워크 예외 매핑 — 도메인 전용 예외는 각 advice가 자기 테이블에 추가한다
    public static final Map<Class<? extends Exception>, Mapping> GENERIC = Map.of(
            SecurityException.class,                       new Mapping(HttpStatus.FORBIDDEN,   "Access Denied"),
            IllegalStateException.class,                   new Mapping(HttpStatus.BAD_REQUEST, "Invalid State"),
            NoSuchElementException.class,                  new Mapping(HttpStatus.NOT_FOUND,   "Resource Not Found"),
            IllegalArgumentException.class,                new Mapping(HttpStatus.BAD_REQUEST, "Invalid Request"),
            MissingServletRequestParameterException.class, new Mapping(HttpStatus.BAD_REQUEST, "Bad Request"),
            MethodArgumentTypeMismatchException.class,     new Mapping(HttpStatus.BAD_REQUEST, "Bad Request"),
            DateTimeParseException.class,                  new Mapping(HttpStatus.BAD_REQUEST, "Invalid Date Format"),
            // 요청 바디 파싱 실패(잘못된 JSON, enum에 없는 값 등) — 매핑 누락 시 catch-all이 500으로 처리해 클라이언트 오류가 서버 오류로 잘못 보고됨
            HttpMessageNotReadableException.class,         new Mapping(HttpStatus.BAD_REQUEST, "Malformed Request"),
            // 존재하지 않는 정적 리소스·경로(취약점 스캐너의 /actuator/** probe 등) — 매핑 없으면 catch-all이 500 + 오류 로그로 처리해 오염
            NoResourceFoundException.class,                new Mapping(HttpStatus.NOT_FOUND,   "Not Found")
    );

    private ProblemDetailMappings() {}

    // GENERIC + 프로세스 고유 매핑 병합 — 중복 키는 specific 우선, 결과는 불변 맵
    public static Map<Class<? extends Exception>, Mapping> withGeneric(Map<Class<? extends Exception>, Mapping> specific) {
        Map<Class<? extends Exception>, Mapping> merged = new HashMap<>(GENERIC);
        merged.putAll(specific);
        return Map.copyOf(merged);
    }

    // 클래스 계층 탐색 — @ExceptionHandler는 서브클래스도 매치하므로(assignability 기준) 테이블 조회도 exact-class가 아닌
    // 상위 클래스 탐색이어야 등록된 예외의 향후 서브클래스가 매칭된다. 매핑 없으면 null — 호출부가 500 폴백 여부를 결정한다
    public static Mapping resolve(Exception ex, Map<Class<? extends Exception>, Mapping> mappings) {
        Class<?> cls = ex.getClass();
        while (cls != null && Exception.class.isAssignableFrom(cls)) {
            @SuppressWarnings("unchecked")
            Mapping m = mappings.get((Class<? extends Exception>) cls);
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

    // 두 advice의 catch-all 공통 본문 — 클라이언트 이탈 → 매핑 테이블 → 미매핑 500 순으로 처리한다.
    // 이벤트 발행은 platform이 sharedkernel을 참조할 수 없어 호출자가 넘기는 onUnmapped Consumer(보고·로그 담당)에 남긴다
    public static ProblemDetail catchAll(Exception ex, Map<Class<? extends Exception>, Mapping> mappings, Consumer<Exception> onUnmapped) {
        // 클라이언트가 응답 수신 전 연결을 끊으면 직렬화·응답 쓰기가 실패해 catch-all로 떨어진다 — 기록 없이 종료
        if (isClientDisconnect(ex)) {
            log.debug("클라이언트 연결 끊김으로 응답 미완: {}", ex.getMessage());
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Client Disconnected", "");
        }
        // 매핑 테이블 조회 — 클래스 계층 탐색으로 서브클래스도 상위 매핑 적용, 매핑 있으면 4xx 응답(오류 보고 없음)
        Mapping m = resolve(ex, mappings);
        if (m != null) {
            return problem(m.status(), m.title(), ex.getMessage());
        }
        // 매핑 없는 미처리 예외 — 호출자가 보고·로그를 수행한 뒤 500
        onUnmapped.accept(ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "예기치 않은 오류가 발생했습니다");
    }

    // ProblemDetail 생성 헬퍼 — 모든 핸들러에서 반복되는 3줄 보일러플레이트 제거
    public static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
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
