package com.kista.domain.model.user;

import java.time.Instant;
import java.util.UUID;

public record FcmDeviceToken(
        UUID id,
        UUID userId,
        String token,       // FCM 등록 토큰
        String platform,    // WEB | ANDROID | IOS
        Instant createdAt
) {}
