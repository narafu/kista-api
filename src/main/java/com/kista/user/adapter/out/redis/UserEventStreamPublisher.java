package com.kista.user.adapter.out.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;


// 기존 로컬 리스너(finance cascade/UserDeletedNotifier/UserFcmCleanupListener,
// UserNotifyProfileSyncListener는 이제 trading-core 전용)는 그대로 두고, trading-core에
// 내구성 있게 전달하기 위한 Redis Stream 발행만 추가한다 — 교체가 아니라 추가.
// fallbackExecution=true: 트랜잭션 밖에서 발행되는 경우에도 유실 없이 발행(복제본 동기화는
// 조용한 유실보다 낫다는 기존 UserNotifyProfileSyncListener 판단과 동일).
@Component
@RequiredArgsConstructor
public class UserEventStreamPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onUserDeleted(UserDeletedEvent event) {
        add(RedisStreamConfig.USER_DELETED_STREAM, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onProfileChanged(UserNotifyProfileChangedEvent event) {
        add(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, event);
    }

    // 이벤트를 JSON payload 1필드로 감싸 XADD — 봉투·트리밍 규약은 platform RedisStreams(trading-core 구독측과 공용).
    // 텔레그램 봇 토큰 등 평문 payload가 무기한 쌓이지 않도록 MAXLEN 근사 트리밍(최근 1000건 보존)
    private void add(String streamKey, Object event) {
        RedisStreams.add(redisTemplate, streamKey, objectMapper.writeValueAsString(event));
    }
}
