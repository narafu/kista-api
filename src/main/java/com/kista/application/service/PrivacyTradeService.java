package com.kista.application.service;

import com.kista.domain.model.privacy.PrivacyCurrentBase;
import com.kista.domain.port.in.GetPrivacyCurrentBaseUseCase;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
class PrivacyTradeService implements GetPrivacyCurrentBaseUseCase {

    private final PrivacyTradePort privacyTradePort;

    @Override
    @Transactional(readOnly = true)
    public PrivacyCurrentBase getPrivacyCurrentBase() {
        // 오늘 이후 거래일 중 가장 미래의 기준가 반환
        return privacyTradePort.findCurrentBase()
                .orElseThrow(() -> new NoSuchElementException("오늘 이후 날짜의 기준 매매표가 없습니다"));
    }
}
