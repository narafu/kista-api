package com.kista.broker.domain.model;

import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.sharedkernel.Broker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerApiExceptionTest {

    @Test
    @DisplayName("KIS/Toss 예외는 벤더와 표기를 BrokerApiException으로 노출한다")
    void vendorExposedThroughBaseType() {
        BrokerApiException kis = new KisApiException("KIS 오류", null);
        BrokerApiException toss = new TossApiException("Toss 오류", null);

        assertThat(kis.vendor()).isEqualTo(Broker.KIS);
        assertThat(kis.vendorLabel()).isEqualTo("KIS");
        assertThat(toss.vendor()).isEqualTo(Broker.TOSS);
        assertThat(toss.vendorLabel()).isEqualTo("Toss");
    }

    @Test
    @DisplayName("409 세부 사유는 벤더 중립 상위 타입에서 판정된다")
    void conflictDeterminedOnBaseType() {
        BrokerApiException filled = new TossApiException("x", null, BrokerApiException.Conflict.ALREADY_FILLED);
        BrokerApiException canceled = new TossApiException("x", null, BrokerApiException.Conflict.ALREADY_CANCELED);

        assertThat(filled.isAlreadyFilledConflict()).isTrue();
        assertThat(filled.isAlreadyCanceledConflict()).isFalse();
        assertThat(canceled.isAlreadyCanceledConflict()).isTrue();
        assertThat(new KisApiException("x", null).isAlreadyFilledConflict()).isFalse();
    }
}
