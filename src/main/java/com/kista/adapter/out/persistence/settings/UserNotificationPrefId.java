package com.kista.adapter.out.persistence.settings;

import java.io.Serializable;
import java.util.UUID;

// JPA 복합키 — (userId, type) 조합으로 알림 선호도 행을 식별
public record UserNotificationPrefId(UUID userId, String type) implements Serializable {}
