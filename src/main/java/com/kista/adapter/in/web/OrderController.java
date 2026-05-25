package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.NextOrdersResponse;
import com.kista.adapter.in.web.dto.ReservationOrderRequest;
import com.kista.domain.model.kis.ReservationOrderReceipt;
import com.kista.domain.port.in.GetNextOrdersUseCase;
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

@Tag(name = "주문", description = "미국 해외주식 예약주문 접수 및 다음 주문 미리보기")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceReservationOrderUseCase placeReservationOrderUseCase;
    private final GetNextOrdersUseCase getNextOrders;

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

    // 다음 주문 미리보기 — DB 미저장, 휴장일·잔고 부족 무관하게 강제 계산
    @Operation(
            summary = "다음 주문 미리보기",
            description = """
                    현재 시점 기준으로 KIS 잔고·현재가를 조회하여 무한매수법 전략을 계산합니다.
                    휴장일·잔고 부족 여부와 무관하게 항상 강제 계산하며, 결과는 DB에 저장되지 않습니다.
                    장 마감 후에도 KIS API가 마지막 체결가를 반환하므로 별도 종가 처리가 없습니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/orders/preview")
    public NextOrdersResponse getNext(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        try {
            GetNextOrdersUseCase.Result result = getNextOrders.preview(accountId, userId);
            return NextOrdersResponse.from(result.tradeDate(), result.position(), result.orders());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KIS API 호출 실패: " + e.getMessage());
        }
    }
}
