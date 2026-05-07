package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.DailyTransactionResult;

import java.time.LocalDate;

public interface KisDailyTransactionPort {
    // 기간 내 일별거래내역 조회 (CTOS4001R)
    DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account);
}
