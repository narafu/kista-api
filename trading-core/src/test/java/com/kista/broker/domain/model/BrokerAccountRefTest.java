package com.kista.broker.domain.model;

import com.kista.sharedkernel.Broker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrokerAccountRef 단위 테스트")
class BrokerAccountRefTest {

    @Test
    @DisplayName("toString은 appKey·secretKey·계좌번호 평문을 노출하지 않는다")
    void toString_masks_credentials() {
        UUID id = UUID.randomUUID();
        BrokerAccountRef ref = new BrokerAccountRef(id, "PLAIN-APP-KEY", "PLAIN-SECRET-KEY", "74420614-01", null, Broker.KIS);

        String text = ref.toString();

        assertThat(text)
                .doesNotContain("PLAIN-APP-KEY", "PLAIN-SECRET-KEY", "74420614")
                .isEqualTo("BrokerAccountRef[id=" + id + ", broker=KIS, accountNo=****1401, appKey=***, secretKey=***]");
    }
}
