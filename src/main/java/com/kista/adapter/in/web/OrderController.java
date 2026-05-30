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

    // 다음 주문 미리보기 — DB 미저장, 휴장일·전략 무관하게 스케줄러 INSERT 대상을 강제 계산
    @Operation(
            summary = "다음 주문 미리보기",
            description = """
                    스케줄러가 다음 매매일 orders 테이블에 INSERT할 내용을 미리 반환합니다.
                    INFINITE·PRIVACY 전략 모두 지원하며, 결과는 DB에 저장되지 않습니다.
                    휴장일 체크를 수행하지 않아 언제든 강제 계산되며, 잔고 부족·기준매매표 미수신 등은 skipReason으로 반환됩니다.
                    INFINITE: KIS 현재가 조회 후 전략 계산. PRIVACY: 오늘자 기준매매표 조회 후 전략 계산.
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
            return NextOrdersResponse.from(result);
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
