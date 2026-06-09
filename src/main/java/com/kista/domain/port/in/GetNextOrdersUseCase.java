package com.kista.domain.port.in;

import com.kista.domain.model.order.NextOrdersPreview;

import java.util.UUID;

public interface GetNextOrdersUseCase {

    NextOrdersPreview preview(UUID accountId, UUID requesterId);
}
