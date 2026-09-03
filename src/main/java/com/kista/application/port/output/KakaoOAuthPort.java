package com.kista.application.port.output;

public interface KakaoOAuthPort {
    String exchangeCodeForToken(String code, String redirectUri);
    KakaoUserInfo getUserInfo(String kakaoAccessToken);

    record KakaoUserInfo(String kakaoId, String nickname, String email) {}
}
