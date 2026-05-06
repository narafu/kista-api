package com.kista.domain.port.in;

import com.kista.domain.model.User;

import java.util.UUID;

public interface GetUserUseCase {
    User getById(UUID id);
    User getByKakaoId(String kakaoId);
}
