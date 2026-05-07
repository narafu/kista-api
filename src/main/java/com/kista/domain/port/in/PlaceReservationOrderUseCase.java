package com.kista.domain.port.in;

import com.kista.domain.model.ReservationOrderCommand;
import com.kista.domain.model.ReservationOrderReceipt;

import java.util.UUID;

public interface PlaceReservationOrderUseCase {
    ReservationOrderReceipt place(UUID accountId, UUID requesterId, ReservationOrderCommand command);
}
