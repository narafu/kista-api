// trading 매매 알림 모듈(순수 리스너·게이트웨이 sink) — trading·privacy가 발행하는 이벤트를 구독해 텔레그램·FCM·Redis Pub/Sub로 내보낸다. trading은 이 모듈을 모른다(단방향).
// NamedInterface 없음(외부 소비자 0). 이 프로세스(kista-trading)가 trading.event_publication EPR 재발행 소유자다.
@org.springframework.modulith.ApplicationModule
package com.kista.tradingnotify;
