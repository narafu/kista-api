package com.kista.support;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

// 컨트롤러 @WebMvcTest 공통 인증 헬퍼
// 사용: import static com.kista.support.WebMvcTestSupport.*
public final class WebMvcTestSupport {

    public static final UUID DEV_USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEV_ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private WebMvcTestSupport() {}

    // 빈 권한 USER 토큰 — @AuthenticationPrincipal UUID로 바인딩
    public static UsernamePasswordAuthenticationToken userToken(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }

    // ROLE_USER 권한 포함 — hasRole("USER") SecurityConfig 검증용
    public static UsernamePasswordAuthenticationToken userTokenWithRole(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // ROLE_ADMIN 권한 포함 — /api/admin/** 접근용
    public static UsernamePasswordAuthenticationToken adminToken(UUID adminId) {
        return new UsernamePasswordAuthenticationToken(adminId, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
