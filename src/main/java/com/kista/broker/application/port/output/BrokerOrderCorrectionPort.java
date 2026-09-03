package com.kista.broker.application.port.output;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;

public interface BrokerOrderCorrectionPort {
    void cancel(CancelInstruction instruction, Account account);
    OrderResult place(OrderInstruction instruction, Account account);
}
