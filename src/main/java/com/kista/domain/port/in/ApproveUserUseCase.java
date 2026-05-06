package com.kista.domain.port.in;

import java.util.UUID;

public interface ApproveUserUseCase {
    void approve(UUID userId);  // PENDING/REJECTED → ACTIVE
    void reject(UUID userId);   // PENDING → REJECTED
    void reapply(UUID userId);  // REJECTED → PENDING (재신청)
}
