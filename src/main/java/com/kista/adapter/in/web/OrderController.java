package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.ReservationOrderRequest;
import com.kista.domain.model.ReservationOrderReceipt;
import com.kista.domain.port.in.PlaceReservationOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "예약주문", description = "미국 해외주식 예약주문 접수")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceReservationOrderUseCase placeReservationOrderUseCase;

    // 미국 해외주식 예약주문 접수 (TTTT3014U 매수 / TTTT3016U 매도)
    @Operation(summary = "예약주문 접수", description = "KIS API를 통해 미국 해외주식 예약주문 접수. 매수: TTTT3014U, 매도: TTTT3016U.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "예약주문 접수 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @PostMapping("/reservation-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationOrderReceipt placeReservationOrder(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
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
