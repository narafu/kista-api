package com.kista.shared.platform.redis;

// 발행 측(trading-core notify)과 구독 측(api.notify)이 같은 스트림 키·컨슈머 그룹 문자열을 쓰도록 강제하는 상수 홀더 —
// RedisPubSubConfig(채널명 상수)와 동일한 목적. Stream 자체(XADD/컨슈머 그룹 생성)는 각자
// 발행측/구독측 어댑터가 담당하고 여긴 문자열만 둔다.
public final class RedisStreamConfig {

    public static final String PUSH_NOTIFICATION_STREAM = "stream:user.push-notification.requested"; // 체결 푸시 요청 → FCM 발송
    public static final long PUSH_NOTIFICATION_MAXLEN = 10_000; // 구독 측 하루 다운을 가정한 상한 — 초과 시 오래된 것부터 근사 트리밍, 무한 적재 방지
    public static final String ROOT_CONSUMER_GROUP = "root"; // push-notification 구독 그룹(web·worker 인스턴스 공용)

    private RedisStreamConfig() {
    }
}
