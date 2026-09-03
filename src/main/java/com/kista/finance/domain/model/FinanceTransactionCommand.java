package com.kista.finance.domain.model;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record FinanceTransactionCommand(
        UUID categoryId,
        LocalDate transactionDate,
        long amount,
        String memo // null 허용
) {}
