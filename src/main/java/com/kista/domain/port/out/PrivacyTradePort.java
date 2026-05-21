package com.kista.domain.port.out;

import com.kista.domain.port.in.FidaOrderRequest;

public interface PrivacyTradePort {
    // FIDA 수신 데이터를 기준 매매표(master) + 주문 명세(details)로 저장
    void saveMasterWithDetails(FidaOrderRequest request);
}
