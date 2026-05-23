package com.kista.domain.port.in;

import com.kista.domain.model.privacy.FidaOrderRequest;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;

public interface ExecuteFidaOrderUseCase {
    PrivacyTradeSaveResult execute(FidaOrderRequest request);
}
