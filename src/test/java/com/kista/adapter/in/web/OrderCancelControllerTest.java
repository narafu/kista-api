package com.kista.adapter.in.web;

import com.kista.domain.model.order.OrderCancelException;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.TradingExecutionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderCancelController.class)
@Execution(ExecutionMode.SAME_THREAD)
class OrderCancelControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean TradingExecutionUseCase tradingExecution;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    void cancelOrder_success_returns204() throws Exception {
        doNothing().when(tradingExecution).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent()); // 204
    }

    @Test
    void cancelOrder_anonymous_returns401() throws Exception {
        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()))
                .andExpect(status().isUnauthorized()); // 401
    }

    @Test
    void cancelOrder_notOwner_returns403() throws Exception {
        doThrow(new SecurityException("소유권 불일치"))
                .when(tradingExecution).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden()); // 403
    }

    @Test
    void cancelOrder_notFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("주문 없음"))
                .when(tradingExecution).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNotFound()); // 404
    }

    @Test
    void cancelOrder_notPlaced_returns409() throws Exception {
        doThrow(new OrderCancelException("PLACED 상태 주문만 취소 가능합니다"))
                .when(tradingExecution).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isConflict()); // 409
    }
}
