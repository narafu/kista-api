package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeritzResponseTest {

    @Test
    void whole_quantity_is_null_and_blank_safe() {
        assertThat(MeritzResponse.wholeQuantity(null)).isZero();
        assertThat(MeritzResponse.wholeQuantity(" ")).isZero();
        assertThat(MeritzResponse.wholeQuantity("417.0000000000")).isEqualTo(417);
    }

    @Test
    void sellable_quantity_shortage_code_is_insufficient_funds_rejection() {
        var e = (MeritzApiException) new MeritzResponse("1543", "매도가능수량이 없습니다. 잔고를 확인해주세요.", null, null, null, null).failure("주문");

        assertThat(e.isRejected()).isTrue();
        assertThat(e.isInsufficientFunds()).isTrue();
        assertThat(BrokerApiException.isInsufficientFundsRejection(e)).isTrue();
    }

    @Test
    void other_rejection_code_is_not_insufficient_funds() {
        var e = (MeritzApiException) new MeritzResponse("9999", "기타", null, null, null, null).failure("주문");

        assertThat(e.isRejected()).isTrue();
        assertThat(e.isInsufficientFunds()).isFalse();
    }
}
