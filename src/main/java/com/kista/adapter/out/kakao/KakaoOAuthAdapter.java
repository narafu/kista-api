package com.kista.adapter.out.kakao;

import com.kista.domain.port.out.KakaoOAuthPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthAdapter implements KakaoOAuthPort {

    private final RestTemplate kakaoRestTemplate;
    private final KakaoProperties kakaoProperties;

    @Override
    public String exchangeCodeForToken(String code, String redirectUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", kakaoProperties.clientId());
        body.add("redirect_uri", redirectUri);
        body.add("code", code);
        if (kakaoProperties.clientSecret() != null && !kakaoProperties.clientSecret().isBlank()) {
            body.add("client_secret", kakaoProperties.clientSecret());
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = kakaoRestTemplate.postForEntity(
                "https://kauth.kakao.com/oauth/token", request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("카카오 토큰 교환 실패: " + response.getStatusCode());
        }
        return (String) response.getBody().get("access_token");
    }

    @Override
    public KakaoUserInfo getUserInfo(String kakaoAccessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(kakaoAccessToken);

        ResponseEntity<Map> response = kakaoRestTemplate.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("카카오 사용자 정보 조회 실패");
        }

        Map<?, ?> responseBody = response.getBody();
        String kakaoId = String.valueOf(responseBody.get("id"));
        String nickname = extractNickname(responseBody);
        return new KakaoUserInfo(kakaoId, nickname);
    }

    @SuppressWarnings("unchecked")
    private String extractNickname(Map<?, ?> body) {
        Map<?, ?> properties = (Map<?, ?>) body.get("properties");
        if (properties != null && properties.get("nickname") instanceof String n) return n;

        Map<?, ?> account = (Map<?, ?>) body.get("kakao_account");
        if (account != null) {
            Map<?, ?> profile = (Map<?, ?>) account.get("profile");
            if (profile != null && profile.get("nickname") instanceof String n) return n;
        }
        return "사용자";
    }
}
