package com.kista.adapter.out.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AesCryptoService — AES-256-GCM 암호화 테스트")
class AesCryptoServiceTest {

    private AesCryptoService service;

    @BeforeEach
    void setUp() {
        // 테스트용 32바이트(256bit) 키 (Base64 인코딩)
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        service = new AesCryptoService(testKey);
    }

    @Test
    @DisplayName("암호화 후 복호화 시 원본 문자열 반환")
    void encrypt_then_decrypt_returns_original() {
        String original = "74420614";
        String encrypted = service.encrypt(original);
        assertThat(service.decrypt(encrypted)).isEqualTo(original);
    }

    @Test
    @DisplayName("KIS App Key 같은 긴 문자열도 암복호화 가능")
    void encrypt_long_string() {
        String appKey = "PSGxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        assertThat(service.decrypt(service.encrypt(appKey))).isEqualTo(appKey);
    }

    @Test
    @DisplayName("동일 입력이라도 매번 다른 암호문 생성 (랜덤 IV)")
    void same_input_produces_different_ciphertext() {
        String input = "test-secret";
        String e1 = service.encrypt(input);
        String e2 = service.encrypt(input);
        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    @DisplayName("잘못된 암호문으로 복호화 시 예외 발생")
    void decrypt_invalid_ciphertext_throws() {
        assertThatThrownBy(() -> service.decrypt("invalid-base64!!"))
                .isInstanceOf(IllegalStateException.class);
    }
}
