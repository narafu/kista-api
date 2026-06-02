package com.kista.adapter.in.web;

import com.kista.domain.port.in.CancelOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "주문 취소", description = "개별 주문 취소")
@RestController
@RequiredArgsConstructor
@Slf4j
public class OrderCancelController {

    private final CancelOrderUseCase cancelOrderUseCase;

    // 특정 주문 1건 취소 — PLACED 상태 주문만 가능
    @Operation(summary = "개별 주문 취소")
    @DeleteMapping("/api/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal UUID userId) {
        try {
            cancelOrderUseCase.cancelOrder(orderId, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); // 409 PLACED 아닌 경우
        }
    }
}
