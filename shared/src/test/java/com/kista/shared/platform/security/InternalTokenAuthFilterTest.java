package com.kista.shared.platform.security;

import com.kista.shared.platform.internalapi.InternalApiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenAuthFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // 주어진 설정 토큰·요청 헤더로 필터를 돌려 체인 통과 여부를 돌려준다
    private static boolean passes(String configured, String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/ping");
        if (header != null) {
            request.addHeader(InternalApiProperties.TOKEN_HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new InternalTokenAuthFilter(configured).doFilter(request, response, chain);
        boolean passed = chain.getRequest() != null;
        assertThat(response.getStatus()).isEqualTo(passed ? 200 : 401);
        return passed;
    }

    @Test
    void matchingToken_passesWithInternalRole() throws Exception {
        assertThat(passes("s3cret-토큰", "s3cret-토큰")).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_INTERNAL");
    }

    @Test
    void wrongMissingOrPrefixToken_is401() throws Exception {
        assertThat(passes("s3cret", "s3creT")).isFalse(); // 같은 길이, 다른 값
        assertThat(passes("s3cret", "s3c")).isFalse(); // 접두사
        assertThat(passes("s3cret", "s3cret-extra")).isFalse();
        assertThat(passes("s3cret", null)).isFalse(); // 헤더 없음
    }

    @Test
    void blankConfiguredToken_rejectsEverything() throws Exception {
        assertThat(passes("", "")).isFalse();
        assertThat(passes("", null)).isFalse();
    }
}
