package com.kista.application.service.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BrokerCallGuard 단위 테스트")
class BrokerCallGuardTest {

    @Test
    @DisplayName("Supplier가 정상 반환하면 그 결과를 그대로 반환한다")
    void wrap_success_returnsSupplierResult() {
        String result = BrokerCallGuard.wrap("테스트 호출", () -> "OK");

        assertThat(result).isEqualTo("OK");
    }

    @Test
    @DisplayName("Supplier가 예외를 던지면 IllegalStateException으로 래핑하고 cause를 보존한다")
    void wrap_failure_wrapsAsIllegalStateException() {
        RuntimeException cause = new RuntimeException("브로커 응답 실패");

        assertThatThrownBy(() -> BrokerCallGuard.wrap("전일종가 조회", () -> {
            throw cause;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("증권사 API")
                .hasCause(cause);
    }
}
