package com.kista.application.service.privacy;

import com.kista.common.TradeDateConverter;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final PrivacyTradePort privacyTradePort;

    @Override
    public PrivacyTradeSaveResult execute(FidaOrderCommand command) {
        // FIDA는 UTC(=US 거래일) 일자 송신 → KST로 변환 후 도메인 전달
        // Persistence adapter가 다시 KST→UTC 변환하므로 원본 FIDA 일자가 DB에 정확히 저장됨
        FidaOrderCommand kstCommand = new FidaOrderCommand(
                TradeDateConverter.toKst(command.tradeDate()),
                command.ticker(),
                command.currentCycleStart(),
                command.currentCycleRealizedPnl(),
                command.avgPrice(),
                command.holdings(),
                command.orders()
        );
        return privacyTradePort.saveMasterWithDetails(kstCommand);
    }
}
