# 배치 스케줄러 시간표

이 문서는 각 모듈 `adapter/in/schedule` 패키지의 배치 실행 시점을 정리한다.
스케쥴러 빈은 `scheduler.enabled=true`(`SCHEDULER_ENABLED`)일 때만 등록되며 프로세스별로 갈린다 — 매매(TradingOpen/Close)·캘린더 스케쥴러는 `:trading-core`라 `kista-trading`에서, 나머지(KB Land·시세·finance·토큰 정리)는 root `app.jar`의 `kista-scheduler`에서 실행된다.
`kista-api`는 `scheduler.enabled=false`라 어느 스케쥴러도 등록하지 않는다(`deploy/server/docker-compose.yml`).
모든 시각은 `KST` 기준이다.

## 거래 관련

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 장 개시 스케쥴러 | 월~금 22:30 KST | 2h | 개장용 주문 생성 및 선접수 |
| 마감 매매 스케쥴러 | 화~토 04:30 KST | 3h | 마감용 주문 생성 및 실행 |

## 시세 및 지수

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 공포탐욕지수 수집 스케쥴러 | 매일 00:00 KST, 12:00 KST | 30m | Fear & Greed 지수 수집/저장 |
| 벤치마크 ETF 지수 종가 동기화 스케쥴러 | 매일 09:00 KST | 30m | ETF/지수 종가 저장 |

## 캘린더

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 기동 시 캘린더 초기 적재 | 앱 기동 직후 | 1h | 당해 연도 포함 3년치 적재 |
| 연간 캘린더 갱신 스케쥴러 | 매년 1월 1일 00:00 KST | 1h | 당해 연도 포함 3년치 갱신 |
| 월간 캘린더 갱신 스케쥴러 | 매월 1일 01:00 KST | 30m | 해당 월 데이터 최신화 |

## 토큰 정리

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 만료 RT 정리 스케쥴러 | 매일 04:00 KST | 30m | 만료 refresh token 삭제 |
| 회전 RT 정리 스케쥴러 | 매일 03:05 KST | 30m | grace 기간 초과 회전 token 삭제 |

## 외부 데이터

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| KB Land 아파트 5분위 매매평균가격 수집 스케쥴러 | 매주 토요일 08:00 KST | 30m | 주택 벤치마크(월간 5분위 가격) 수집/저장 |
| KB Land 아파트 주간 매매가격지수 수집 스케쥴러 (최근 2년) | 매주 토요일 08:10 KST | 30m | 주택 벤치마크(주간 매매가격지수) 수집/저장 |
| KB Land 아파트 주간 매매가격지수 월간 풀 리프레시 스케쥴러 (20년 전체) | 매월 1일 08:20 KST | 30m | 과거 기준일 값 사후 보정 반영 (수동: `POST /api/admin/scheduler/kbland-price-index/full-refresh`) |

## 알림

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 가계부 등록 리마인더 (`FinanceRegistrationReminderScheduler`) | 매월 말일 21:00 KST | 30m | 이번 달 가계부 미등록 사용자 알림 |

## Redis Stream 복구 (cron 아님)

`RedisStreamSubscriber` 서브클래스(root `AppErrorStreamConsumer`·`PushNotificationStreamConsumer`, trading-core `UserDeletedStreamConsumer`·`UserNotifyProfileStreamConsumer`)의 `@Scheduled(fixedDelay = 5분)`이 끊긴 구독 재기동 + pending XCLAIM 복구를 한다. `scheduler.enabled` 게이팅이 없어 각 프로세스(role)마다 동작한다.

## 참고

- 모든 스케줄러는 `scheduler.enabled=true`일 때만 동작한다 — 운영에서는 `kista-scheduler`·`kista-trading` 컨테이너만 이 값이 true다(Stream 복구 제외).
- 거래 스케줄러는 cron 시간 외에 내부 대기 로직을 추가로 가질 수 있다.
- 실제 운영 흐름은 [docs/agents/workflow.md](workflow.md)도 함께 보면 된다.
