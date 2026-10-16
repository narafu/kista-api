package com.kista.tradingcore.trading.adapter.in.redis;

import com.kista.shared.platform.redis.RedisStreamConfig;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// root가 stream:user.notify-profile.changed로 발행한 UserNotifyProfileChangedEvent를 trading-core 로컬 이벤트로 재발행하는 구독자(user_notify_profile 복제본 동기화 트리거).
// 실패 정책·기동·주기 복구는 UserEventStreamConsumer 공통 — 옛 스냅샷 재적용은 UserNotifyProfileSyncListener의 occurredAt 버전 가드가 막으므로
// 일시 오류도 재시도한다(활성 여부 같은 복제본 변경이 유실되면 비활성 사용자의 전략이 계속 매매된다)
@Component
public class UserNotifyProfileStreamConsumer extends UserEventStreamConsumer {

    private final UserEventStreamBridge bridge; // 역직렬화 + 로컬 이벤트 재발행 위임

    public UserNotifyProfileStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                           UserEventStreamBridge bridge, TradingErrorReportPort errorReportPort) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, errorReportPort);
        this.bridge = bridge;
    }

    // 봉투 payload를 로컬 이벤트로 재발행 — 예외는 베이스가 warn 후 ackOnFailure 정책으로 ack 여부 결정
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        bridge.handleProfileChangedRecord(record);
    }
}
