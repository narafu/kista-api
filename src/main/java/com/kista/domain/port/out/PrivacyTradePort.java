package com.kista.domain.port.out;

import com.kista.domain.model.privacy.FidaOrderRequest;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;

public interface PrivacyTradePort {
    // FIDA 수신 데이터를 기준 매매표(master) + 주문 명세(details)로 저장
    // 동일 (tradeDate, ticker)가 이미 존재하면 비교 후 일치 시 created=false, 불일치 시 PrivacyTradeConflictException
    PrivacyTradeSaveResult saveMasterWithDetails(FidaOrderRequest request);
}
