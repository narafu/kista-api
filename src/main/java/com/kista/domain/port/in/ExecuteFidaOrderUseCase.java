package com.kista.domain.port.in;

import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;

public interface ExecuteFidaOrderUseCase {
    PrivacyTradeSaveResult execute(FidaOrderCommand command);
}
