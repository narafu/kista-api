package com.kista.tradingcore.broker.application.service;

import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
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

    @Test
    @DisplayName("증권사 타입 예외는 래핑하지 않고 그대로 전파한다 — 503/422/429 상태코드 보존")
    void wrap_brokerTypedException_rethrownAsIs() {
        RuntimeException apiFailure = new com.kista.tradingcore.support.StubBrokerApiException("KIS", "KIS 토큰 발급 실패");
        RuntimeException credential = new BrokerCredentialException();
        RuntimeException rateLimit = new BrokerRateLimitException();

        for (RuntimeException e : new RuntimeException[]{apiFailure, credential, rateLimit}) {
            assertThatThrownBy(() -> BrokerCallGuard.wrap("전일종가 조회", () -> {
                throw e;
            })).isSameAs(e);
        }
    }

    @Test
    @DisplayName("tryGet은 정상 결과를 감싸 반환하고, 예외(증권사 타입 포함)는 삼켜 empty로 돌려준다 — fail-open")
    void tryGet_swallowsAnyRuntimeException() {
        assertThat(BrokerCallGuard.tryGet("조회", () -> "OK")).contains("OK");
        assertThat(BrokerCallGuard.tryGet("조회", () -> null)).isEmpty();
        assertThat(BrokerCallGuard.tryGet("조회", () -> {
            throw new BrokerRateLimitException();
        })).isEmpty();
        assertThat(BrokerCallGuard.tryGet("조회", () -> {
            throw new IllegalStateException("boom");
        })).isEmpty();
    }
}
