package com.kista.application.service;

import com.kista.application.config.AdminBootstrapProperties;
import com.kista.domain.model.User;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
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

        try {
            // 신규 사용자 등록 시도
            return registerUser.register(kakaoUser.kakaoId(), kakaoUser.nickname(), UUID.randomUUID());
        } catch (DataIntegrityViolationException e) {
            // 중복 kakaoId → 기존 사용자 조회 후 ADMIN promote 확인
            log.debug("중복 가입 시도 → 기존 사용자 반환: kakaoId={}", kakaoUser.kakaoId());
            User existing = getUser.getByKakaoId(kakaoUser.kakaoId());
            // 기존 사용자가 ADMIN seed인데 아직 USER이면 idempotent promote
            if (bootstrapProps.isAdmin(existing.kakaoId()) && existing.role() != UserRole.ADMIN) {
                log.info("기존 사용자 ADMIN promote: kakaoId={}", existing.kakaoId());
                existing = userRepository.save(new User(
                        existing.id(), existing.kakaoId(), existing.nickname(), UserStatus.ACTIVE, UserRole.ADMIN,
                        existing.telegramBotToken(), existing.telegramChatId(),
                        existing.createdAt(), existing.updatedAt(), existing.lastReappliedAt()
                ));
            }
            return existing;
        }
    }
}
