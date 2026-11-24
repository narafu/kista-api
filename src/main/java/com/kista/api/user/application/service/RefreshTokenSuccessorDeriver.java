package com.kista.api.user.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

// RTR 후계 RT 결정적 유도 — 같은 RT로 재시도하면 항상 같은 후계가 나오게 한다 (AES 키에서 도메인 분리 파생 키 사용)
@Component
public class RefreshTokenSuccessorDeriver {

    private static final String HMAC = "HmacSHA256";
    private static final String DOMAIN = "kista-rt-successor-v1"; // 파생 키 도메인 분리 라벨

    private final SecretKeySpec subKey; // HMAC(aesKey, DOMAIN) — 생성 시 1회 계산

    public RefreshTokenSuccessorDeriver(@Value("${crypto.aes-key}") String base64Key) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), HMAC));
            this.subKey = new SecretKeySpec(mac.doFinal(DOMAIN.getBytes(StandardCharsets.UTF_8)), HMAC);
        } catch (Exception e) {
            throw new IllegalStateException("refresh token 후계 키 파생에 실패했습니다.", e);
        }
    }

    // base64url(no pad) HMAC-SHA256 — 43자
    public String successorOf(String rawToken) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(subKey);
            byte[] digest = mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("refresh token 후계 계산에 실패했습니다.", e);
        }
    }
}
