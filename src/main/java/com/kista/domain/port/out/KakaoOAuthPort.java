package com.kista.domain.port.out;

public interface KakaoOAuthPort {
    String exchangeCodeForToken(String code, String redirectUri);
    KakaoUserInfo getUserInfo(String kakaoAccessToken);

    record KakaoUserInfo(String kakaoId, String nickname) {}
}
