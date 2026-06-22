package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossCommissionRate;

import java.util.List;

public interface TossCommissionsPort {
    List<TossCommissionRate> getCommissions(Account account);
}
