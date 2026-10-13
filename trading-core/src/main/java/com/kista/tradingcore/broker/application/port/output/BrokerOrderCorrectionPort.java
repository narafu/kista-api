package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;

public interface BrokerOrderCorrectionPort {
    void cancel(CancelInstruction instruction, BrokerAccountRef account);
    OrderResult place(OrderInstruction instruction, BrokerAccountRef account);
}
