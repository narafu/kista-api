package com.kista.tradingcore.broker.domain.model;

import com.kista.tradingcore.broker.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerApiExceptionTest {

    @Test
    @DisplayName("Toss 예외는 벤더 표기를 BrokerApiException으로 노출한다")
    void vendorExposedThroughBaseType() {
        BrokerApiException toss = new TossApiException("Toss 오류", null);

        assertThat(toss.vendorLabel()).isEqualTo("Toss");
    }

    @Test
    @DisplayName("거절 플래그가 선 벤더 예외와 자격증명·토큰 한도 예외는 확정 거절")
    void isRejection_trueFor_rejectedToss_credential_rateLimit() {
        assertThat(BrokerApiException.isRejection(TossApiException.rejected("x", null))).isTrue();
        assertThat(BrokerApiException.isRejection(new BrokerCredentialException())).isTrue();
        assertThat(BrokerApiException.isRejection(new BrokerRateLimitException())).isTrue();
    }

    @Test
    @DisplayName("플래그 없는 벤더 예외와 그 밖의 예외는 결과 불명")
    void isRejection_falseFor_plainBrokerException_and_others() {
        assertThat(BrokerApiException.isRejection(new TossApiException("x", null))).isFalse();
        assertThat(BrokerApiException.isRejection(new IllegalStateException())).isFalse();
    }
}
