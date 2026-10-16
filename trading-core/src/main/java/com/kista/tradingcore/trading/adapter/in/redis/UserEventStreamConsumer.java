package com.kista.tradingcore.trading.adapter.in.redis;

import com.kista.shared.platform.redis.RedisStreamConfig;
import com.kista.shared.platform.redis.RedisStreamSubscriber;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.core.JacksonException;

import java.util.concurrent.TimeUnit;

// root가 발행한 user.* 스트림(UserDeletedStreamConsumer·UserNotifyProfileStreamConsumer) 구독자 공통 정책 —
// 구독·ack·재기동·XCLAIM 복구는 platform RedisStreamSubscriber가 맡고 여기는 실패 정책·기동·5분 주기 복구 배선만 가진다.
// 역직렬화 실패(poison)만 ack로 버리고, 재발행(이벤트 저장) 단계의 일시 오류는 pending으로 남겨 XCLAIM 재시도(최대 24회 ≈ 2시간) 후
// 포기하며 관리자에게 알린다. 프로필 upsert도 occurredAt 버전 가드가 옛 스냅샷을 거르므로 재시도가 안전하다.
// 재시도 범위 주의: 로컬 리스너(UserCascadeListener·UserNotifyProfileSyncListener)는 재발행 트랜잭션의 AFTER_COMMIT에서 돌아
// 실패가 여기까지 전파되지 않는다(메시지는 ack) — 그 실패는 trading.event_publication에 FAILED로 남고 UserEventResubmitScheduler가 재발행한다.
// 두 서브클래스의 컨슈머 접두사가 같아 복구 컨슈머 이름(trading-core-recovery)도 같지만 스트림이 달라 pending 추적이 충돌하지 않는다
abstract class UserEventStreamConsumer extends RedisStreamSubscriber {

    private static final int MAX_DELIVERIES = 24; // 복구 주기 5분 x 24회 = 약 2시간 재시도 후 포기(ack)

    private final String streamKey;                         // 포기 알림 문구용 스트림 키
    private final TradingErrorReportPort errorReportPort;   // 재시도 포기 관리자 알림

    UserEventStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                            String streamKey, TradingErrorReportPort errorReportPort) {
        super(connectionFactory, redisTemplate, streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, "trading-core");
        this.streamKey = streamKey;
        this.errorReportPort = errorReportPort;
    }

    // 기동 시 구독 시작 — Redis 장애로 실패해도 삼키고 매매 프로세스는 그대로 기동(다음 복구 주기에 재시도)
    @PostConstruct
    void init() {
        start();
    }

    @Override
    protected boolean ackOnFailure(MapRecord<String, String, String> record, Exception e) {
        // 재시도해도 결과가 같은 결정적 실패(역직렬화 오류·payload 필드 누락 IAE/NPE)는 poison으로 ack, 그 외(DB·트랜잭션 일시 오류)만 재시도
        return e instanceof JacksonException || e instanceof IllegalArgumentException || e instanceof NullPointerException;
    }

    @Override
    protected int maxDeliveries() {
        return MAX_DELIVERIES;
    }

    // 재시도 한도 초과로 포기한 메시지 — 복제본(탈퇴 정리·활성 여부)이 낡은 채 남으므로 관리자에게 알린다
    @Override
    protected void onGiveUp(RecordId recordId) {
        errorReportPort.reportError(new IllegalStateException(
                streamKey + " 메시지 재시도 한도 초과로 포기 — 복제본 미반영, 수동 확인 필요 recordId=" + recordId));
    }

    // 5분마다 끊긴 구독 재기동 + pending 복구
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void scheduledRecovery() {
        recoverPending();
    }
}
