package com.kista.domain.port.out;

import com.kista.domain.model.Execution;

import java.time.LocalDate;
import java.util.List;

public interface KisExecutionPort {
    List<Execution> getExecutions(String token, LocalDate date);
}
