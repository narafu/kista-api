package com.kista.domain.port.in;

import java.util.UUID;

public interface DeleteAccountUseCase {
    void delete(UUID accountId, UUID requesterId);
}
