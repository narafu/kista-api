package com.kista.domain.port.in;

import com.kista.domain.port.out.PrivacyTradeSaveResult;

public interface ExecuteFidaOrderUseCase {
    PrivacyTradeSaveResult execute(FidaOrderRequest request);
}
