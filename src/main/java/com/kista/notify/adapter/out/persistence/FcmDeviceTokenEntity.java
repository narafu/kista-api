package com.kista.notify.adapter.out.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fcm_device_tokens", schema = "public")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FcmDeviceTokenEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
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
