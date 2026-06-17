package com.kista.domain.port.out;

import java.time.Duration;
import java.util.UUID;

public interface BlacklistPort {
    void add(UUID userId, Duration ttl); // AT TTL과 동일 기간 차단
    boolean isBlacklisted(UUID userId);
}
