package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final KisOrderPort kisOrderPort;

    @Override
    public void execute(FidaOrderRequest request) {
        // V2: 계좌 컨텍스트 필요 — AccountController 통해 계좌별 전략 제어 사용 권장
        throw new UnsupportedOperationException("V2에서는 계좌별 전략 API(/api/accounts/{id}/strategy)를 사용하세요");
    }
}
