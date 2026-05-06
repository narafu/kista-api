package com.kista.domain.port.in;

import com.kista.domain.model.User;

import java.util.UUID;

public interface RegisterUserUseCase {
    // 카카오 로그인 후 신규 가입(PENDING) 또는 기존 사용자 조회
    User register(String kakaoId, String nickname, UUID supabaseUid);
}
