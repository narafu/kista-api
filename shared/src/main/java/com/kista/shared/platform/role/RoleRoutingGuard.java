package com.kista.shared.platform.role;

import com.kista.shared.platform.web.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

// worker에서는 springdoc 경로도 비엔진이라 503이다.
// role과 맞지 않는 핸들러로 온 요청을 인증 전에 503 WRONG_ROLE로 거절한다 — web은 엔진 경로, worker는 엔진 아닌 경로가 대상(ALL은 통과).
// 엔진 경로 = 핸들러 클래스가 com.kista.tradingcore 패키지이거나 @ConditionalOnWorkerRole이 붙은 것. 핸들러가 없거나 MVC 컨트롤러가 아니면(actuator 등) 통과
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // Spring Security 체인(-100)보다 앞
public class RoleRoutingGuard extends OncePerRequestFilter {

    private static final String ENGINE_PACKAGE = "com.kista.tradingcore."; // 매매 엔진 모듈 루트 — 05 1-2 표의 엔진 = 지금의 trading-core
    // ProblemDetail 응답 본문 — ProblemDetailExceptionHandler와 같은 필드(type·title·status·detail·code)
    private static final String BODY = """
            {"type":"about:blank","title":"Wrong Role","status":503,"detail":"이 서버 역할에서는 처리하지 않는 요청입니다.","code":"%s"}"""
            .formatted(ErrorCode.WRONG_ROLE.name());

    private final KistaRole role;
    private final List<RequestMappingHandlerMapping> mappings; // 일반 MVC 매핑 + 컨트롤러 엔드포인트 매핑

    public RoleRoutingGuard(KistaRole role, List<RequestMappingHandlerMapping> mappings) {
        this.role = role;
        this.mappings = mappings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return role == KistaRole.ALL;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Boolean engine = isEngineRequest(request);
        // 핸들러를 못 찾았거나 컨트롤러가 아니면 role과 무관 — 통과
        boolean allowed = engine == null || (role == KistaRole.WORKER ? engine : !engine); // web은 비엔진, worker는 엔진만 허용
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }

    // 엔진 컨트롤러면 true, 그 외 컨트롤러면 false, 컨트롤러가 아니면 null
    private Boolean isEngineRequest(HttpServletRequest request) throws ServletException {
        // PathPattern 매핑은 파싱된 경로가 필요 — 필터 단계엔 아직 없으니 만들고, 우리가 만들었으면 치운다(DispatcherServlet이 다시 만든다)
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        if (parsedHere) ServletRequestPathUtils.parseAndCache(request);
        try {
            HandlerExecutionChain handlerChain = null;
            for (RequestMappingHandlerMapping mapping : mappings) {
                try {
                    handlerChain = mapping.getHandler(request);
                } catch (ServletException e) {
                    // 405·415·406 등 매칭 실패 예외는 "핸들러 없음" — 다음 매핑으로, 끝내 없으면 통과해 DispatcherServlet이 기존 응답을 그린다
                    continue;
                }
                if (handlerChain != null) break;
            }
            if (handlerChain == null || !(handlerChain.getHandler() instanceof HandlerMethod handler)) return null;
            Class<?> type = ClassUtils.getUserClass(handler.getBeanType()); // 프록시 대비
            return type.getName().startsWith(ENGINE_PACKAGE)
                    || AnnotatedElementUtils.hasAnnotation(type, ConditionalOnWorkerRole.class);
        } catch (ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        } finally {
            if (parsedHere) ServletRequestPathUtils.clearParsedRequestPath(request);
        }
    }
}
