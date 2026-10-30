package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.CancelOutcome;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;

public interface BrokerOrderCorrectionPort {
    // 이미 종결(체결 완료·취소·장 마감 만료)된 주문은 예외가 아니라 ALREADY_FILLED/ALREADY_CANCELED로 반환한다 — 그 외 실패만 BrokerApiException
    CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account);
    OrderResult place(OrderInstruction instruction, BrokerAccountRef account);
}
