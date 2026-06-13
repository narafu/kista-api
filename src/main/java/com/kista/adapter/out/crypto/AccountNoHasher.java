package com.kista.adapter.out.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

// 계좌번호 결정론적 해시 — 전역 중복 체크용 (AES-GCM은 비결정론적이므로 별도 HMAC-SHA256 사용)
@Component
public class AccountNoHasher {

    private final SecretKeySpec hmacKey;

    public AccountNoHasher(@Value("${crypto.aes-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.hmacKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    // HMAC-SHA256 결과를 64자 hex 문자열로 반환
    public String hash(String accountNo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] digest = mac.doFinal(accountNo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("계좌번호 해시 계산 실패", e);
        }
    }
}
