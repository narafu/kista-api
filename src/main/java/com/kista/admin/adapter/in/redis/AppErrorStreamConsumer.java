package com.kista.admin.adapter.in.redis;

import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// trading-core가 stream:app.error로 push한 AppErrorRaisedEvent를 app_error_logs에 저장하는 구독자 —
// 과거 ErrorLogInternalController(POST /api/internal/errors, trading-core→root 동기 HTTP)를 대체한다.
// root의 두 role(kista-api·kista-scheduler)이 같은 컨슈머 그룹으로 나눠 받는다(메시지당 1회 처리, 컨슈머 이름은
// 인스턴스마다 고유). 저장 실패 격리는 AppErrorLogPort.save() 계약이 보장하고, ack 실패까지 여기서 삼켜 리스너가
// 절대 예외를 던지지 않는다. 구독은 cancelOnError=false로 등록해 일시 오류(XREADGROUP/XACK 실패)에 끊기지 않고,
// 그래도 구독이 죽거나 기동 자체가 실패한 경우는 5분 주기 recoverPending()이 재기동한다 — XCLAIM 복구는
// 이미 읽힌 pending만 다루므로 컨슈머 자체가 살아 있어야 미전달 메시지가 소비된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class AppErrorStreamConsumer implements DisposableBean {

    private static final Duration IDLE_THRESHOLD = Duration.ofSeconds(60); // 이 시간 이상 ack 없는 pending만 복구 대상
    private static final String RECOVERY_CONSUMER = "root-recovery"; // 복구 전용 컨슈머 이름
    private static final int CLAIM_BATCH_SIZE = 100; // 복구 1회 최대 claim 건수

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppErrorLogPort appErrorLogPort;
    private final String consumerName = "root-" + UUID.randomUUID(); // pending 추적용 인스턴스 고유 이름
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription; // 살아 있는지(isActive) 판정 기준 — 컨테이너 isRunning만으로는 구독 취소를 못 본다

    @PostConstruct
    void start() {
        if (subscription != null && subscription.isActive()) return;
        stopContainer();
        try {
            RedisStreams.ensureGroup(redisTemplate, RedisStreamConfig.APP_ERROR_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP);
            var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                    .builder()
                    .pollTimeout(Duration.ofSeconds(2))
                    .build();
            container = StreamMessageListenerContainer.create(connectionFactory, options);
            // cancelOnError=false — 폴링·ack의 일시 오류로 구독이 조용히 끊기지 않게 한다(Spring Data Redis 기본값은 취소)
            var request = StreamMessageListenerContainer.StreamReadRequest
                    .builder(StreamOffset.create(RedisStreamConfig.APP_ERROR_STREAM, ReadOffset.lastConsumed()))
                    .consumer(Consumer.from(RedisStreamConfig.ROOT_CONSUMER_GROUP, consumerName))
                    .autoAcknowledge(false)
                    .cancelOnError(t -> false)
                    .errorHandler(t -> log.warn("app.error 스트림 폴링 오류 — 구독 유지: {}", t.getMessage()))
                    .build();
            subscription = container.register(request, this::handle);
            container.start();
            log.info("app.error 스트림 컨슈머 시작 — consumer={}", consumerName);
        } catch (Exception e) {
            stopContainer(); // 부분 생성물 폐기 — 다음 recoverPending()이 처음부터 재시도한다
            log.error("app.error 스트림 컨슈머 시작 실패 — 5분 뒤 재시도할 때까지 오류 로그 저장이 지연된다", e);
        }
    }

    // 봉투 payload → AppErrorRaisedEvent → app_error_logs 저장 → ack. 역직렬화 실패는 poison 메시지라 로그만 남기고
    // ack해 영구 재시도를 막는다. ack 실패도 삼킨다(pending으로 남아 XCLAIM 복구 대상이 될 뿐, 구독을 죽이지 않는다)
    public void handle(MapRecord<String, String, String> record) {
        try {
            AppErrorRaisedEvent event = objectMapper.readValue(RedisStreams.payload(record), AppErrorRaisedEvent.class);
            appErrorLogPort.save(event.errorType(), event.message(), event.stackTrace(), event.context());
        } catch (Exception e) {
            log.warn("app.error 스트림 메시지 처리 실패 — recordId={}: {}", record.getId(), e.getMessage());
        }
        try {
            redisTemplate.opsForStream().acknowledge(RedisStreamConfig.APP_ERROR_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            log.warn("app.error 스트림 ack 실패 — recordId={}: {}", record.getId(), e.getMessage());
        }
    }

    // 5분마다 (1) 기동 실패했거나 끊긴 구독을 재기동하고 (2) 읽고 ack 전 죽어 pending으로 남은 메시지를 복구 —
    // SchedulerJobRunner를 쓰지 않는 이유는 STARTED/COMPLETED 알림(SchedulerNotifier)이 5분마다 텔레그램에 쌓이기 때문
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void recoverPending() {
        start();
        try {
            int recovered = reclaimPending(IDLE_THRESHOLD);
            if (recovered > 0) log.warn("app.error 스트림 pending 복구 처리 — {}건", recovered);
        } catch (Exception e) {
            log.warn("app.error 스트림 pending 복구 실패: {}", e.getMessage());
        }
    }

    int reclaimPending(Duration idleThreshold) {
        return RedisStreams.reclaimPending(redisTemplate, RedisStreamConfig.APP_ERROR_STREAM, RedisStreamConfig.ROOT_CONSUMER_GROUP,
                RECOVERY_CONSUMER, idleThreshold, CLAIM_BATCH_SIZE, this::handle);
    }

    private void stopContainer() {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.debug("app.error 스트림 컨테이너 정지 중 오류 무시: {}", e.getMessage());
            }
        }
        container = null;
        subscription = null;
    }

    @Override
    public void destroy() {
        stopContainer();
    }
}
