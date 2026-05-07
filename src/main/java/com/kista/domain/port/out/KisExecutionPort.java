package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.Execution;

import java.time.LocalDate;
import java.util.List;

public interface KisExecutionPort {
    List<Execution> getExecutions(LocalDate date, Account account);
}
