package com.kista.domain.port.in;

import com.kista.domain.model.order.ReservationOrderCommand;
import com.kista.domain.model.kis.ReservationOrderReceipt;

import java.util.UUID;

public interface PlaceReservationOrderUseCase {
    ReservationOrderReceipt place(UUID accountId, UUID requesterId, ReservationOrderCommand command);
}
