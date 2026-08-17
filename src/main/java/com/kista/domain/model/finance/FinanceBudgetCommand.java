package com.kista.domain.model.finance;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record FinanceBudgetCommand(
        UUID categoryId,
        LocalDate applyStartDate,
        LocalDate applyEndDate, // null이면 무기한
        long amount
) {}
