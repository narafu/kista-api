package com.kista.adapter.in.web.security;

import com.kista.domain.model.auth.TokenConstants;
import com.kista.domain.model.user.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트용 EC P-256 JWK (비공개키 포함) — 프로덕션 키와 무관
@ExtendWith(SpringExtension.class)
@Import(JwtIssuerService.class)
@TestPropertySource(properties = {
    "jwt.signing-key={\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU\",\"y\":\"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0\",\"d\":\"jpsQnnGQmL-YBIffH1136cspYG6-0iY7X1fCE9-E9LI\",\"use\":\"sig\",\"alg\":\"ES256\",\"kid\":\"test-key-1\"}"
})
@DisplayName("JwtIssuerService — ES256 JWT 발급 테스트")
class JwtIssuerServiceTest {

    // 검증용 공개키만 포함한 JWK (비공개키 d 필드 없음)
    private static final String PUBLIC_JWK = "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU\",\"y\":\"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0\"}";

    @Autowired
    JwtIssuerService jwtIssuerService;

    @Test
    @DisplayName("issue() — null이 아닌 비어있지 않은 토큰 반환")
    void issue_returns_non_null_token() {
        String token = jwtIssuerService.issue(UUID.randomUUID(), User.UserRole.USER);

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("issue() — header.payload.signature 세 파트로 구성")
    void issue_token_has_three_parts() {
        String token = jwtIssuerService.issue(UUID.randomUUID(), User.UserRole.USER);

        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("issue() USER — subject가 userId, role 클레임이 USER")
    void issue_user_token_subject_and_role_claim() throws Exception {
        UUID userId = UUID.randomUUID();

        String token = jwtIssuerService.issue(userId, User.UserRole.USER);

        // Nimbus 직접 파싱: EC 공개키 검증 (NimbusJwtDecoder.withPublicKey()는 RSA 전용)
        JWTClaimsSet claims = verifyAndExtractClaims(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getStringClaim("role")).isEqualTo("USER");
    }

    @Test
    @DisplayName("issue() ADMIN — role 클레임이 ADMIN")
    void issue_admin_token_has_admin_role() throws Exception {
        UUID userId = UUID.randomUUID();

        String token = jwtIssuerService.issue(userId, User.UserRole.ADMIN);

        JWTClaimsSet claims = verifyAndExtractClaims(token);

        assertThat(claims.getStringClaim("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("expiresInSeconds() — AT_TTL(1일, 86400초) 반환")
    void expiresInSeconds_returns_900() {
        assertThat(jwtIssuerService.expiresInSeconds()).isEqualTo(TokenConstants.AT_TTL.toSeconds());
    }

    // 공개키로 ES256 서명 검증 후 클레임 반환
    private JWTClaimsSet verifyAndExtractClaims(String token) throws Exception {
        ECKey ecKey = ECKey.parse(PUBLIC_JWK);
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(ecKey));

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, jwkSource));

        return processor.process(SignedJWT.parse(token), null);
    }
}
