package com.kista.domain.model.finance;

// 등록·수정 공용
public record FinanceAccountCommand(
        FinanceAccount.Type accountType,
        String name,
        String accountNo, // null 허용
        String memo        // null 허용
) {}
