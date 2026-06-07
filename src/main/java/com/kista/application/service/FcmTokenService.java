package com.kista.application.service;

import com.kista.domain.port.in.RegisterFcmTokenUseCase;
import com.kista.domain.port.in.UnregisterFcmTokenUseCase;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class FcmTokenService implements RegisterFcmTokenUseCase, UnregisterFcmTokenUseCase {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;

    @Override
    public void register(UUID userId, String token, String platform) {
        fcmDeviceTokenPort.save(userId, token, platform);
    }

    @Override
    public void unregister(UUID userId, String token) {
        fcmDeviceTokenPort.delete(userId, token);
    }
}
