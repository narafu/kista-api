package com.kista.notify.application.port.output;

import java.util.List;
import java.util.UUID;

public interface FcmDeviceTokenPort {
    void save(UUID userId, String token, String platform);
    void delete(UUID userId, String token);
    List<String> findTokensByUserId(UUID userId);
    void deleteAllByUserId(UUID userId); // 탈퇴 cascade — 사용자 소유 FCM 디바이스 토큰 전체 삭제
}
