package com.kista.domain.port.in;

import com.kista.domain.model.User;

public interface KakaoLoginUseCase {
    User login(String code, String redirectUri);
}
