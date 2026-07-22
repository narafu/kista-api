package com.kista.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// SHA-256 다이제스트를 16진 문자열로 변환하는 공용 헬퍼 — RT 해시(TokenService)·Toss token fingerprint(TossRedisTokenStore) 중복 구현 통합
public final class Sha256 {

    private Sha256() {}

    public static String hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘 없음", exception);
        }
    }
}
