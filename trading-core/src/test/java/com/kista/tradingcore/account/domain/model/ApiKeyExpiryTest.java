package com.kista.tradingcore.account.domain.model;

import com.kista.tradingcore.account.domain.model.ApiKeyExpiry.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// kista-ui entities/account/model/apiKeyExpiry.test.ts 기준일(2026-10-18 KST) 이식 — 서버는 KST 날짜 단위로 판정한다
class ApiKeyExpiryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    @Test
    void none_when_no_expiry() {
        assertThat(ApiKeyExpiry.of(null, TODAY)).isEqualTo(new ApiKeyExpiry(Status.NONE, null, null));
    }

    @Test
    void expired_when_days_left_is_zero_or_less() {
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-10-17T23:59:59+09:00"), TODAY))
                .isEqualTo(new ApiKeyExpiry(Status.EXPIRED, LocalDate.of(2026, 10, 17), -1));
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-10-18T00:00:00+09:00"), TODAY))
                .isEqualTo(new ApiKeyExpiry(Status.EXPIRED, TODAY, 0));
    }

    @Test
    void expiring_within_30_days_ok_beyond() {
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-10-19T00:00:00+09:00"), TODAY).status()).isEqualTo(Status.EXPIRING);
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-11-17T00:00:00+09:00"), TODAY))
                .isEqualTo(new ApiKeyExpiry(Status.EXPIRING, LocalDate.of(2026, 11, 17), 30));
        // 날짜 단위 판정 — 같은 KST 날짜면 시각과 무관(ui는 밀리초 기준이라 11-17 00:00:01을 OK로 본다)
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-11-17T23:59:59+09:00"), TODAY).status()).isEqualTo(Status.EXPIRING);
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-11-18T00:00:00+09:00"), TODAY))
                .isEqualTo(new ApiKeyExpiry(Status.OK, LocalDate.of(2026, 11, 18), 31));
    }

    @Test
    void expires_on_is_kst_date_not_utc() {
        // UTC 10-18 16:00 = KST 10-19 01:00
        assertThat(ApiKeyExpiry.of(Instant.parse("2026-10-18T16:00:00Z"), TODAY).expiresOn()).isEqualTo(LocalDate.of(2026, 10, 19));
    }
}
