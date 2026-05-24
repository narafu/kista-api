package com.kista.domain.port.in;

import com.kista.domain.model.privacy.PrivacyCurrentBase;

public interface GetPrivacyCurrentBaseUseCase {
    // trade_date >= 오늘인 행 중 가장 미래의 기준가 반환. 없으면 NoSuchElementException
    PrivacyCurrentBase getPrivacyCurrentBase();
}
