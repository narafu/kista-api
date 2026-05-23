package com.kista.adapter.out.persistence.fcm;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fcm_device_tokens")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FcmDeviceTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;

    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String token; // FCM 등록 토큰

    @Column(nullable = false, length = 10)
    private String platform; // WEB | ANDROID | IOS

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    static FcmDeviceTokenEntity of(UUID userId, String token, String platform) {
        FcmDeviceTokenEntity e = new FcmDeviceTokenEntity();
        e.userId = userId;
        e.token = token;
        e.platform = platform;
        return e;
    }
}
