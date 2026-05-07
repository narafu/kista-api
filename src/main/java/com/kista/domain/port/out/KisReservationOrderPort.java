package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.ReservationOrder;
import com.kista.domain.model.ReservationOrderCommand;
import com.kista.domain.model.ReservationOrderReceipt;

import java.time.LocalDate;
import java.util.List;

public interface KisReservationOrderPort {
    // 기간 내 예약주문 목록 조회 (TTTT3039R)
    List<ReservationOrder> getReservationOrders(LocalDate from, LocalDate to, Account account);

    // 미국 해외주식 예약주문 접수 (매수: TTTT3014U, 매도: TTTT3016U)
    ReservationOrderReceipt placeReservationOrder(ReservationOrderCommand command, Account account);
}
