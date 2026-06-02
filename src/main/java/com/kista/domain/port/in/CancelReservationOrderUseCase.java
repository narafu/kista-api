package com.kista.domain.port.in;

import java.util.UUID;

public interface CancelReservationOrderUseCase {
    // 예약주문 취소 (TTTT3017U). 실패 시 RuntimeException(→503) 전파.
    // 예외: SecurityException(403), NoSuchElementException(404)
    void cancel(UUID accountId, UUID requesterId, String reservationOrderId, String receiptDate);
}
