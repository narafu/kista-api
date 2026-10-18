package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.support.StubBrokerApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoFailureTest {

    @Test
    void credentialBaseTypeIsKeyExpired() {
        CryptoFailure f = CryptoFailure.classify(new BrokerCredentialException());
        assertThat(f).isEqualTo(CryptoFailure.KEY_EXPIRED);
        assertThat(f.reason()).isEqualTo(DcaSkipReason.API_KEY_EXPIRED);
    }

    @Test
    void credentialSubtypesAreRejected() {
        assertThat(CryptoFailure.classify(new BrokerIpNotAllowedException())).isEqualTo(CryptoFailure.REJECTED);
        assertThat(CryptoFailure.classify(new BrokerPermissionDeniedException())).isEqualTo(CryptoFailure.REJECTED);
        assertThat(CryptoFailure.REJECTED.reason()).isEqualTo(DcaSkipReason.EXCHANGE_API_ERROR);
    }

    @Test
    void apiExceptionDependsOnRejectedFlag() {
        assertThat(CryptoFailure.classify(StubBrokerApiException.rejected("거절"))).isEqualTo(CryptoFailure.REJECTED);
        assertThat(CryptoFailure.classify(new StubBrokerApiException("결과 불명"))).isEqualTo(CryptoFailure.TRANSIENT);
    }

    @Test
    void rateLimitAndOthersAreTransient() {
        assertThat(CryptoFailure.classify(new RuntimeException("io"))).isEqualTo(CryptoFailure.TRANSIENT);
        assertThat(CryptoFailure.classify(new BrokerRateLimitException())).isEqualTo(CryptoFailure.TRANSIENT);
    }
}
