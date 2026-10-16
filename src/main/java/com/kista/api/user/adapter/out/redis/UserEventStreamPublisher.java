package com.kista.api.user.adapter.out.redis;

import com.kista.shared.platform.redis.RedisStreamConfig;
import com.kista.shared.platform.redis.RedisStreams;
import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    // XADD 실패는 로그만 — 트랜잭션 안 발행은 AFTER_COMMIT 예외가 원래 삼켜지고, 트랜잭션 밖(fallbackExecution) 발행만
    // 호출자에게 전파돼 이미 커밋된 사용자 요청을 500으로 만들던 비대칭을 없앤다(누락은 다음 변경이나 resyncNotifyProfile 재발행이 바로잡는다)
    private void add(String streamKey, Object event) {
        try {
            RedisStreams.add(redisTemplate, streamKey, objectMapper.writeValueAsString(event));
        } catch (RuntimeException e) {
            log.error("사용자 이벤트 Redis Stream 발행 실패 — trading-core 복제본 미반영: stream={}, event={}, error={}",
                    streamKey, event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
