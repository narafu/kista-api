package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;

import java.time.LocalDate;

// 일별 거래내역 조회 — KIS: CTOS4001R / Toss: execution+commission 조합
public interface DailyTradeCapable {
    DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account);
}
