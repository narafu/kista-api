package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class FidaOrderServiceTest {

    @Mock KisOrderPort kisOrderPort;

    @InjectMocks
    FidaOrderService sut;

    @Test
    void execute_throws_unsupported_in_v2() {
        // V2에서는 계좌 컨텍스트 필요 — 직접 주문 API 사용 불가
        FidaOrderRequest req = new FidaOrderRequest(
                "SOXL", Order.OrderDirection.BUY, 5, new BigDecimal("25.50"));

        assertThatThrownBy(() -> sut.execute(req))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("V2");
    }
}
