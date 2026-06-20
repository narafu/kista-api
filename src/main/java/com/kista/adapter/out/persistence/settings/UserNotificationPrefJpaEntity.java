package com.kista.adapter.out.persistence.settings;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "user_notification_prefs")
@IdClass(UserNotificationPrefId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserNotificationPrefJpaEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID userId; // 사용자 식별자 — 복합 PK의 첫 번째 컬럼

    @Id
    @Column(name = "type", length = 50)
    private String type; // NotificationType 이름 — 복합 PK의 두 번째 컬럼

    @Column(nullable = false)
    private boolean enabled; // true=알림 활성, false=비활성
}
