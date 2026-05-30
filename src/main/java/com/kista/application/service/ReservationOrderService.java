package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.ReservationOrderCommand;
import com.kista.domain.model.kis.ReservationOrderReceipt;
import com.kista.domain.port.in.PlaceReservationOrderUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.KisReservationOrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationOrderService implements PlaceReservationOrderUseCase {

    private final AccountPort accountPort;
    private final KisReservationOrderPort kisReservationOrderPort;

    @Override
    public ReservationOrderReceipt place(UUID accountId, UUID requesterId, ReservationOrderCommand command) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        log.info("예약주문 접수: accountId={}, ticker={}, direction={}, quantity={}, price={}",
                accountId, command.ticker(), command.direction(), command.quantity(), command.price());
        // KIS 예외는 그대로 전파 → 컨트롤러에서 503 처리
        return kisReservationOrderPort.placeReservationOrder(command, account);
    }

}
