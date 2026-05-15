package com.kista.application.service;

import com.kista.domain.model.User;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.KakaoLoginUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import com.kista.domain.port.out.KakaoOAuthPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoLoginService implements KakaoLoginUseCase {

    private final KakaoOAuthPort kakaoOAuthPort;
    private final RegisterUserUseCase registerUser;
    private final GetUserUseCase getUser;

    @Override
    public User login(String code, String redirectUri) {
        // 인가 코드를 카카오 액세스 토큰으로 교환
        String kakaoAccessToken = kakaoOAuthPort.exchangeCodeForToken(code, redirectUri);
        // 카카오 액세스 토큰으로 사용자 정보 조회
        KakaoOAuthPort.KakaoUserInfo kakaoUser = kakaoOAuthPort.getUserInfo(kakaoAccessToken);

        try {
            // 신규 사용자 등록 시도
            return registerUser.register(kakaoUser.kakaoId(), kakaoUser.nickname(), UUID.randomUUID());
        } catch (DataIntegrityViolationException e) {
            // 중복 kakaoId → 기존 사용자 반환 (동시 가입 경쟁 상태 대비)
            log.debug("중복 가입 시도 → 기존 사용자 반환: kakaoId={}", kakaoUser.kakaoId());
            return getUser.getByKakaoId(kakaoUser.kakaoId());
        }
    }
}
