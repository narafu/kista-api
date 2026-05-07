package com.kista.application.service;

import com.kista.domain.model.Account;
import com.kista.domain.model.ReservationOrderCommand;
import com.kista.domain.model.ReservationOrderReceipt;
import com.kista.domain.port.in.PlaceReservationOrderUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisReservationOrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationOrderService implements PlaceReservationOrderUseCase {

    private final AccountRepository accountRepository;
    private final KisReservationOrderPort kisReservationOrderPort;

    @Override
    public ReservationOrderReceipt place(UUID accountId, UUID requesterId, ReservationOrderCommand command) {
        Account account = findAndVerify(accountId, requesterId);
        log.info("예약주문 접수: accountId={}, symbol={}, direction={}, qty={}, price={}",
                accountId, command.symbol(), command.direction(), command.qty(), command.price());
        // KIS 예외는 그대로 전파 → 컨트롤러에서 503 처리
        return kisReservationOrderPort.placeReservationOrder(command, account);
    }

    // 계좌 조회 + 소유권 검증
    private Account findAndVerify(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
        if (!account.userId().equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
        return account;
    }
}
