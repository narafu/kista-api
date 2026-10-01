package com.kista.platform.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.Lifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.UUID;

// Redis Stream 컨슈머 그룹 구독의 공통 골격(비-빈 abstract 베이스) — 구독 시작/정지, cancelOnError=false 등록,
// ack 보장, 끊긴 구독 재기동, XCLAIM pending 복구를 한 곳에 둔다. 서브클래스는 onRecord(도메인 처리)만 구현하고,
// 기동(@PostConstruct → start())과 주기 복구(@Scheduled → recoverPending())는 서브클래스가 배선한다 —
// platform은 @Scheduled/@PostConstruct 프로세스 배선을 갖지 않는다. 베이스를 빈으로 등록하면 서브클래스와 이중 구독이 된다.
// 처리 실패 정책은 훅으로 고른다 — 기본(ackOnFailure=true)은 onRecord 예외를 삼켜 warn만 남기고 ack해 poison 메시지의 영구 재시도를
// 막는다(app.error·푸시처럼 유실 허용 소비자). 정합성 이벤트 소비자는 ackOnFailure를 false로 override해 일시 오류를 pending으로 남기고
// recoverPending()의 XCLAIM 재시도에 맡기되, maxDeliveries로 재시도 한도를 둬 poison의 무한 재시도를 막는다. ack 실패도 삼켜 pending으로 남긴다.
@Slf4j
public abstract class RedisStreamSubscriber implements DisposableBean {

    private static final Duration IDLE_THRESHOLD = Duration.ofSeconds(60); // 이 시간 이상 ack 없는 pending만 복구 대상
    private static final String RECOVERY_CONSUMER = "-recovery"; // 복구 전용 컨슈머 이름 접미사
    private static final int CLAIM_BATCH_SIZE = 100; // 복구 1회 최대 claim 건수

    private final RedisConnectionFactory connectionFactory; // 구독 컨테이너 생성용 연결 팩토리
    protected final StringRedisTemplate redisTemplate;      // ack·XCLAIM·서브클래스 조회용 템플릿
    private final String streamKey;    // 구독할 스트림 키
    private final String group;        // 컨슈머 그룹명
    private final String consumerPrefix; // 컨슈머 이름 접두사(root 등) — 인스턴스별 고유 이름은 접두사 + UUID
    private final String consumerName; // pending 추적용 인스턴스 고유 이름
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription; // 살아 있는지(isActive) 판정 기준 — 컨테이너 isRunning만으로는 구독 취소를 못 본다

    protected RedisStreamSubscriber(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                    String streamKey, String group, String consumerPrefix) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.group = group;
        this.consumerPrefix = consumerPrefix;
        this.consumerName = consumerPrefix + "-" + UUID.randomUUID();
    }

    // 레코드 1건의 도메인 처리 — 예외는 베이스가 삼키고 warn 로그를 남긴 뒤 ackOnFailure 정책에 따라 ack 여부를 정한다
    protected abstract void onRecord(MapRecord<String, String, String> record);

    // 처리 실패 시 ack할지 결정하는 훅 — 기본 true(실패도 ack: 유실 허용 소비자). false면 ack하지 않고 pending으로 남겨 XCLAIM이 재시도한다
    protected boolean ackOnFailure(MapRecord<String, String, String> record, Exception e) {
        return true;
    }

    // pending 재시도 최대 전달 횟수 훅 — 기본 무제한. 초과한 메시지는 reclaimPending이 포기(ack)한다
    protected int maxDeliveries() {
        return Integer.MAX_VALUE;
    }

    // 구독 시작 — 이미 살아 있으면 no-op, 기동 실패는 삼키고 다음 recoverPending()이 처음부터 재시도한다
    public void start() {
        if (subscription != null && subscription.isActive()) return;
        stop();
        try {
            RedisStreams.ensureGroup(redisTemplate, streamKey, group);
            var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                    .builder()
                    .pollTimeout(Duration.ofSeconds(2))
                    .build();
            container = StreamMessageListenerContainer.create(connectionFactory, options);
            // 일시 오류로는 구독을 취소하지 않는다(Spring Data Redis 기본값은 취소) — 단 연결 팩토리가 정지되면 취소한다.
            // 팩토리는 Lifecycle 정지 단계에서 먼저 멈추고 destroy()는 그 뒤라, 취소하지 않으면 폴링이 즉시 실패를 무한 반복한다
            var request = StreamMessageListenerContainer.StreamReadRequest
                    .builder(StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                    .consumer(Consumer.from(group, consumerName))
                    .autoAcknowledge(false)
                    .cancelOnError(t -> connectionFactoryStopped())
                    .errorHandler(t -> log.warn("{} 스트림 폴링 오류 — 구독 유지: {}", streamKey, t.getMessage()))
                    .build();
            subscription = container.register(request, this::handle);
            container.start();
            log.info("{} 스트림 컨슈머 시작 — group={}, consumer={}", streamKey, group, consumerName);
        } catch (Exception e) {
            stop(); // 부분 생성물 폐기 — 다음 recoverPending()이 처음부터 재시도한다
            log.error("{} 스트림 컨슈머 시작 실패 — 다음 복구 주기까지 소비가 지연된다", streamKey, e);
        }
    }

    // 연결 팩토리가 Lifecycle을 구현하고 정지(STOPPING/STOPPED 포함) 상태인지 — 종료 시 폴링 구독 취소 판정
    boolean connectionFactoryStopped() {
        return connectionFactory instanceof Lifecycle lifecycle && !lifecycle.isRunning();
    }

    // onRecord 처리 → ack. 처리 예외와 ack 예외를 각각 삼켜 리스너가 절대 예외를 던지지 않게 한다
    public void handle(MapRecord<String, String, String> record) {
        try {
            onRecord(record);
        } catch (Exception e) {
            // 재시도 대기(ackOnFailure=false)면 ack 없이 pending으로 남겨 XCLAIM 복구에 맡긴다
            if (!ackOnFailure(record, e)) {
                log.warn("{} 스트림 메시지 처리 실패 — pending 유지(재시도 대기) recordId={}: {}", streamKey, record.getId(), e.getMessage());
                return;
            }
            log.warn("{} 스트림 메시지 처리 실패 — recordId={}: {}", streamKey, record.getId(), e.getMessage());
        }
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
        } catch (Exception e) {
            log.warn("{} 스트림 ack 실패 — recordId={}: {}", streamKey, record.getId(), e.getMessage());
        }
    }

    // 끊기거나 기동 실패한 구독을 재기동하고, 읽고 ack 전 죽어 pending으로 남은 메시지를 XCLAIM으로 복구 —
    // 서브클래스의 @Scheduled 메서드가 주기적으로 호출한다. XCLAIM은 이미 읽힌 pending만 다루므로 컨슈머 자체가 살아 있어야 한다
    public void recoverPending() {
        start();
        try {
            int recovered = reclaimPending(IDLE_THRESHOLD);
            if (recovered > 0) log.warn("{} 스트림 pending 복구 처리 — {}건", streamKey, recovered);
        } catch (Exception e) {
            log.warn("{} 스트림 pending 복구 실패: {}", streamKey, e.getMessage());
        }
    }

    // idleThreshold 이상 ack 없는 pending을 복구 컨슈머로 claim해 handle로 재처리 — 처리 건수 반환
    public int reclaimPending(Duration idleThreshold) {
        return RedisStreams.reclaimPending(redisTemplate, streamKey, group,
                consumerPrefix + RECOVERY_CONSUMER, idleThreshold, CLAIM_BATCH_SIZE, maxDeliveries(), this::handle);
    }

    // 구독 컨테이너 정지 — 정지 중 오류는 무시하고 상태를 초기화해 재기동 가능하게 한다
    public void stop() {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.debug("{} 스트림 컨테이너 정지 중 오류 무시: {}", streamKey, e.getMessage());
            }
        }
        container = null;
        subscription = null;
    }

    // 빈 종료 시 구독 정리
    @Override
    public void destroy() {
        stop();
    }
}
