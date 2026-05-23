package com.kista.application.service;

import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.model.privacy.FidaOrderRequest;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final PrivacyTradePort privacyTradePort;

    @Override
    public PrivacyTradeSaveResult execute(FidaOrderRequest request) {
        return privacyTradePort.saveMasterWithDetails(request);
    }
}
