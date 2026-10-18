package com.kista.api.admin.adapter.in.web.dto;

import com.kista.shared.contract.account.AccountSummaryResponse;
import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAccountResponseTest {

    AccountSummaryResponse upbit = new AccountSummaryResponse(UUID.randomUUID(), UUID.randomUUID(), null, Broker.UPBIT, Instant.now());
    AccountSummaryResponse kis = new AccountSummaryResponse(UUID.randomUUID(), UUID.randomUUID(), "12345678-01", Broker.KIS, Instant.now());

    @Test
    void upbitAccountNoIsNull() {
        assertThat(AdminAccountResponse.from(upbit, null, List.of()).accountNoMasked()).isNull();
        AdminAccountItem item = AdminAccountItem.from(upbit, Map.of());
        assertThat(item.accountNoMasked()).isNull();
        assertThat(item.accountNo()).isNull();
    }

    @Test
    void kisAccountNoMasked() {
        assertThat(AdminAccountResponse.from(kis, null, List.of()).accountNoMasked()).isEqualTo("****7801");
        assertThat(AdminAccountItem.from(kis, Map.of()).accountNoMasked()).isEqualTo("****7801");
    }
}
