package com.kista.adapter.out.persistence.settings;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserSettingsJpaEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID userId; // 사용자 식별자 — PK이자 FK (users.id)

    @Column(name = "balance_check_enabled", nullable = false)
    private boolean balanceCheckEnabled; // true=실잔고 검증, false=바이패스
}
