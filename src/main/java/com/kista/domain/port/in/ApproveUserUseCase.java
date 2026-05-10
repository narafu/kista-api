package com.kista.domain.port.in;

import java.util.UUID;

public interface ApproveUserUseCase {
    void approve(UUID userId);  // PENDING/REJECTED → ACTIVE
    void reject(UUID userId);   // PENDING → REJECTED (lastReappliedAt 갱신)
    void reapply(UUID userId);  // REJECTED(24h)/PENDING(1h) → PENDING
}
