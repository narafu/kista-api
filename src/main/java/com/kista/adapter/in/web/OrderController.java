package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.ReservationOrderRequest;
import com.kista.domain.model.ReservationOrderReceipt;
import com.kista.domain.port.in.PlaceReservationOrderUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceReservationOrderUseCase placeReservationOrderUseCase;

    // 미국 해외주식 예약주문 접수 (TTTT3014U 매수 / TTTT3016U 매도)
    @PostMapping("/reservation-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationOrderReceipt placeReservationOrder(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestBody @Valid ReservationOrderRequest request) {
        try {
            return placeReservationOrderUseCase.place(accountId, userId, request.toCommand());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
    }
}
