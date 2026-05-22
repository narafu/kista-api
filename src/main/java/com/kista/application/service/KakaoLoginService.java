package com.kista.application.service;

import com.kista.application.config.AdminBootstrapProperties;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.KakaoLoginUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import com.kista.domain.port.out.KakaoOAuthPort;
import com.kista.domain.port.out.UserRepository;
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
    private final UserRepository userRepository; // idempotent ADMIN promote용
    private final AdminBootstrapProperties bootstrapProps; // ADMIN seed 목록

    @Override
    public User login(String code, String redirectUri) {
        // 인가 코드를 카카오 액세스 토큰으로 교환
        String kakaoAccessToken = kakaoOAuthPort.exchangeCodeForToken(code, redirectUri);
        // 카카오 액세스 토큰으로 사용자 정보 조회
        KakaoOAuthPort.KakaoUserInfo kakaoUser = kakaoOAuthPort.getUserInfo(kakaoAccessToken);

        User user;
        try {
            // 신규 사용자 등록 시도 (기존 사용자면 그대로 반환)
            user = registerUser.register(kakaoUser.kakaoId(), kakaoUser.nickname(), UUID.randomUUID());
        } catch (DataIntegrityViolationException e) {
            // 동시 가입 경쟁 조건 → 기존 사용자 조회
            log.debug("중복 가입 시도 → 기존 사용자 반환: kakaoId={}", kakaoUser.kakaoId());
            user = getUser.getByKakaoId(kakaoUser.kakaoId());
        }
        // ADMIN seed인데 아직 USER이면 idempotent promote (seed 목록 사후 추가 케이스 포함)
        if (bootstrapProps.isAdmin(user.kakaoId()) && user.role() != User.UserRole.ADMIN) {
            log.info("기존 사용자 ADMIN promote: kakaoId={}", user.kakaoId());
            user = userRepository.save(new User(
                    user.id(), user.kakaoId(), user.nickname(), User.UserStatus.ACTIVE, User.UserRole.ADMIN,
                    user.telegramBotToken(), user.telegramChatId(), user.telegramBotUsername(),
                    user.createdAt(), user.updatedAt(), user.lastReappliedAt(),
                    user.notificationChannel() != null ? user.notificationChannel() : NotificationChannel.TELEGRAM
            ));
        }
        return user;
    }
}
