package com.kista.platform.redis;

// root와 trading-core가 같은 스트림 키·컨슈머 그룹 문자열을 쓰도록 강제하는 상수 홀더 —
// RedisPubSubConfig(채널명 상수)와 동일한 목적. Stream 자체(XADD/컨슈머 그룹 생성)는 각자
// 발행측/구독측 어댑터가 담당하고 여긴 문자열만 둔다. 방향은 스트림마다 다르다:
// user.* 두 스트림은 root→trading-core, app.error는 trading-core→root.
public final class RedisStreamConfig {

    public static final String USER_DELETED_STREAM = "stream:user.deleted";
    public static final String USER_NOTIFY_PROFILE_CHANGED_STREAM = "stream:user.notify-profile.changed";
    public static final String APP_ERROR_STREAM = "stream:app.error"; // trading-core 오류 보고 → root app_error_logs
    public static final String TRADING_CONSUMER_GROUP = "trading-core"; // user.* 스트림 구독 그룹(kista-trading)
    public static final String ROOT_CONSUMER_GROUP = "root"; // app.error 스트림 구독 그룹(kista-api·kista-scheduler 공용)

    private RedisStreamConfig() {
    }
}
