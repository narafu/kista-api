package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;

public interface BrokerOrderCorrectionPort {
    void cancel(CancelInstruction instruction, BrokerAccountRef account);
    OrderResult place(OrderInstruction instruction, BrokerAccountRef account);
}
