package com.kista.application.service;

import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final PrivacyTradePort privacyTradePort;

    @Override
    public void execute(FidaOrderRequest request) {
        privacyTradePort.saveMasterWithDetails(request);
    }
}
