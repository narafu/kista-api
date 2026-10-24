package com.kista.tradingcore.trading.adapter.in.redis;

import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserEventStreamBridgeCompatTest {

    // 롤링 배포 중 구버전 kista-api가 balanceCheckEnabled를 실어 보낸 payload도 예외 없이 읽어야 한다
    @Test
    void 구버전_payload의_balanceCheckEnabled_키는_무시하고_역직렬화한다() {
        UserEventRepublisher republisher = mock(UserEventRepublisher.class);
        UserEventStreamBridge bridge = new UserEventStreamBridge(new ObjectMapper(), republisher);
        UUID userId = UUID.randomUUID();
        String json = "{\"userId\":\"" + userId + "\",\"notificationPrefs\":{},\"balanceCheckEnabled\":false,"
                + "\"active\":true,\"telegramBotToken\":null,\"chatId\":null,\"notificationChannel\":null,\"occurredAt\":null}";
        MapRecord<String, String, String> record = StreamRecords.newRecord().in("s").withId(RecordId.of("1-0"))
                .ofMap(Map.of("payload", json));

        bridge.handleProfileChangedRecord(record);

        ArgumentCaptor<UserNotifyProfileChangedEvent> captor = ArgumentCaptor.forClass(UserNotifyProfileChangedEvent.class);
        verify(republisher).republishProfileChanged(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().active()).isTrue();
    }
}
