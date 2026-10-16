package com.kista.tradingcore.trading.adapter.in.redis;

import com.kista.shared.platform.redis.RedisStreamConfig;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// root가 stream:user.deleted로 발행한 UserDeletedEvent를 trading-core 로컬 이벤트로 재발행하는 구독자(cascade soft-delete 트리거).
// 실패 정책·기동·주기 복구는 UserEventStreamConsumer 공통 — 이 클래스는 스트림 키와 브릿지 위임만 가진다
@Component
public class UserDeletedStreamConsumer extends UserEventStreamConsumer {

    private final UserEventStreamBridge bridge; // 역직렬화 + 로컬 이벤트 재발행 위임

    public UserDeletedStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                     UserEventStreamBridge bridge, TradingErrorReportPort errorReportPort) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.USER_DELETED_STREAM, errorReportPort);
        this.bridge = bridge;
    }

    // 봉투 payload를 로컬 이벤트로 재발행 — 예외는 베이스가 warn 후 ackOnFailure 정책으로 ack 여부 결정
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        bridge.handleUserDeletedRecord(record);
    }
}
