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

    AccountSummaryResponse meritz = new AccountSummaryResponse(UUID.randomUUID(), UUID.randomUUID(), "메리츠 키전용", null, Broker.MERITZ, Instant.now());
    AccountSummaryResponse toss = new AccountSummaryResponse(UUID.randomUUID(), UUID.randomUUID(), "토스 메인", "12345678-01", Broker.TOSS, Instant.now());

    @Test
    void keyOnlyAccountNoIsNull() {
        assertThat(AdminAccountResponse.from(meritz, null, List.of()).accountNoMasked()).isNull();
        AdminAccountItem item = AdminAccountItem.from(meritz, Map.of());
        assertThat(item.accountNoMasked()).isNull();
        assertThat(item.accountNo()).isNull();
    }

    @Test
    void accountNicknameExposed() {
        assertThat(AdminAccountResponse.from(meritz, null, List.of()).nickname()).isEqualTo("메리츠 키전용");
        assertThat(AdminAccountItem.from(meritz, Map.of()).nickname()).isEqualTo("메리츠 키전용");
    }

    @Test
    void stockAccountNoMasked() {
        assertThat(AdminAccountResponse.from(toss, null, List.of()).accountNoMasked()).isEqualTo("****7801");
        assertThat(AdminAccountItem.from(toss, Map.of()).accountNoMasked()).isEqualTo("****7801");
    }
}
