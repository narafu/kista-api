package com.kista.adapter.out.heartbeat;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 스케쥴러별 healthchecks.io 핑 URL — 미설정(빈 값)이면 핑 생략
@ConfigurationProperties(prefix = "heartbeat")
public record HeartbeatProperties(String openUrl, String closeUrl) {}
