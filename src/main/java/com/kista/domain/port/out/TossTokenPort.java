package com.kista.domain.port.out;

import java.util.UUID;

public interface TossTokenPort {
    // clientId: Toss client_id, clientSecret: Toss client_secret
    String getToken(UUID accountId, String clientId, String clientSecret);
}
