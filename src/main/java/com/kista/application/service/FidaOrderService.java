package com.kista.application.service;

import com.kista.common.TradeDateConverter;
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
        // FIDA는 UTC(=US 거래일) 일자 송신 → KST로 변환 후 도메인 전달
        // Persistence adapter가 다시 KST→UTC 변환하므로 원본 FIDA 일자가 DB에 정확히 저장됨
        FidaOrderRequest kstRequest = new FidaOrderRequest(
                TradeDateConverter.toKst(request.tradeDate()),
                request.ticker(),
                request.currentCycleStart(),
                request.currentCycleRealizedPnl(),
                request.avgPrice(),
                request.holdings(),
                request.orders()
        );
        return privacyTradePort.saveMasterWithDetails(kstRequest);
    }
}
