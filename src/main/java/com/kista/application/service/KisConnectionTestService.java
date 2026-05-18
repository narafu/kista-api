package com.kista.application.service;

import com.kista.domain.port.in.KisConnectionTestUseCase;
import com.kista.domain.port.out.KisConnectionTestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KisConnectionTestService implements KisConnectionTestUseCase {

    private final KisConnectionTestPort connectionTestPort; // 아웃바운드 포트 — adapter.out 구현체 주입

    @Override
    public boolean test(String appKey, String appSecret) {
        return connectionTestPort.test(appKey, appSecret);
    }
}
