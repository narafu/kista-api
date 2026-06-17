package com.kista.adapter.in.web.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieHelper {

    private static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth"; // refresh/logout 엔드포인트에만 전송
    private static final long RT_MAX_AGE = 432_000L; // 120시간 = 5일

    @Value("${app.cookie.secure:true}")
    private boolean secure;

    @Value("${app.cookie.same-site:None}")
    private String sameSite;

    // RT를 HttpOnly 쿠키로 발급
    public ResponseCookie issue(String rawRefreshToken) {
        return ResponseCookie.from(COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(RT_MAX_AGE)
                .build();
    }

    // 로그아웃 시 쿠키 삭제 (maxAge=0)
    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    // 요청 쿠키에서 rawToken 추출
    public String extract(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
