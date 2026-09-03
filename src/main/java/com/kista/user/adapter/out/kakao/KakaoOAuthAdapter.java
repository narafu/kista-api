package com.kista.user.adapter.out.kakao;

import com.kista.user.application.port.output.KakaoOAuthPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthAdapter implements KakaoOAuthPort {

    private final RestClient kakaoRestClient;
    private final KakaoProperties kakaoProperties;

    @Override
    public String exchangeCodeForToken(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", kakaoProperties.clientId());
        body.add("redirect_uri", redirectUri);
        body.add("code", code);
        if (kakaoProperties.clientSecret() != null && !kakaoProperties.clientSecret().isBlank()) {
            body.add("client_secret", kakaoProperties.clientSecret());
        }

        // 4xx/5xx는 RestClient 기본 오류 핸들러가 HttpClientErrorException/HttpServerErrorException으로 전파
        Map<?, ?> response = kakaoRestClient.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("카카오 토큰 교환 실패: 응답 본문 없음");
        }
        return (String) response.get("access_token");
    }

    @Override
    public KakaoUserInfo getUserInfo(String kakaoAccessToken) {
        Map<?, ?> responseBody = kakaoRestClient.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(kakaoAccessToken))
                .retrieve()
                .body(Map.class);

        if (responseBody == null) {
            throw new IllegalStateException("카카오 사용자 정보 조회 실패");
        }

        Map<?, ?> account = (Map<?, ?>) responseBody.get("kakao_account");
        String kakaoId = String.valueOf(responseBody.get("id"));
        String nickname = extractNickname(responseBody, account);
        String email = extractEmail(account);
        return new KakaoUserInfo(kakaoId, nickname, email);
    }

    private String extractNickname(Map<?, ?> body, Map<?, ?> account) {
        Map<?, ?> properties = (Map<?, ?>) body.get("properties");
        if (properties != null && properties.get("nickname") instanceof String n) return n;

        if (account != null) {
            Map<?, ?> profile = (Map<?, ?>) account.get("profile");
            if (profile != null && profile.get("nickname") instanceof String n) return n;
        }
        return "사용자";
    }

    // 이메일 동의를 하지 않은 사용자는 kakao_account.email 자체가 없어 null 반환
    private String extractEmail(Map<?, ?> account) {
        if (account != null && account.get("email") instanceof String email) return email;
        return null;
    }
}
