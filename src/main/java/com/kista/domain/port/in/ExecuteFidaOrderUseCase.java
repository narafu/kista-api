package com.kista.domain.port.in;

import java.util.UUID;

public interface ExecuteFidaOrderUseCase {
    UUID execute(FidaOrderRequest request);
}
