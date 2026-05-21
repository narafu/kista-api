package com.kista.domain.port.out;

import com.kista.domain.port.in.FidaOrderRequest;

import java.util.UUID;

public interface PrivacyTradePort {
    // FIDA 수신 데이터를 기준 매매표(master) + 주문 명세(details)로 저장; 생성된 master ID 반환
    UUID saveMasterWithDetails(FidaOrderRequest request);
}
