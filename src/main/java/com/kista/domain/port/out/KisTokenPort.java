package com.kista.domain.port.out;

import java.util.UUID;

public interface KisTokenPort {
    String getToken(UUID accountId, String appKey, String appSecret);
}
