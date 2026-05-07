package com.kista.domain.model;

public record ReservationOrderReceipt(
        String kisOrderId,         // 주문번호 (ODNO)
        String reservationOrderId, // 해외예약주문번호 (OVRS_RSVN_ODNO)
        String receiptDate         // 예약주문접수일자 (RSVN_ORD_RCIT_DT)
) {}
