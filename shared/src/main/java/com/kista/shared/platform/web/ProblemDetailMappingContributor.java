package com.kista.shared.platform.web;

import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

// 모듈이 자기 예외의 HTTP 응답을 공용 advice(ProblemDetailExceptionHandler)에 기여하는 빈 — 같은 예외를 두 곳이 기여하면 기동 실패.
// WebMvcConfigurer를 확장하는 이유: @WebMvcTest 슬라이스는 스캔한 빈 중 WebMvcConfigurer 등 web 타입만 올리므로,
// 이 표시가 없으면 슬라이스에서 기여 빈이 빠져 4xx 매핑이 500으로 바뀐다(설정 메서드는 쓰지 않는다)
public interface ProblemDetailMappingContributor extends WebMvcConfigurer {

    // 예외 → status·title·code 표
    Map<Class<? extends Exception>, Mapping> mappings();

    // 표로 못 나타내는 응답 — 기본 없음
    default Map<Class<? extends Exception>, ProblemResponder> responders() {
        return Map.of();
    }
}
