package com.kista.sharedkernel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationChannelTest {

    @Test
    void withTelegram_addsTelegram() {
        assertThat(NotificationChannel.NONE.withTelegram()).isEqualTo(NotificationChannel.TELEGRAM);
        assertThat(NotificationChannel.FCM.withTelegram()).isEqualTo(NotificationChannel.ALL);
        assertThat(NotificationChannel.TELEGRAM.withTelegram()).isEqualTo(NotificationChannel.TELEGRAM);
        assertThat(NotificationChannel.ALL.withTelegram()).isEqualTo(NotificationChannel.ALL);
    }

    @Test
    void withoutTelegram_removesTelegram() {
        assertThat(NotificationChannel.TELEGRAM.withoutTelegram()).isEqualTo(NotificationChannel.NONE);
        assertThat(NotificationChannel.ALL.withoutTelegram()).isEqualTo(NotificationChannel.FCM);
        assertThat(NotificationChannel.NONE.withoutTelegram()).isEqualTo(NotificationChannel.NONE);
        assertThat(NotificationChannel.FCM.withoutTelegram()).isEqualTo(NotificationChannel.FCM);
    }
}
