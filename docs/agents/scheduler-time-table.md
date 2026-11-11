# 배치 스케줄러 시간표

이 문서는 각 모듈 `adapter/in/schedule` 패키지의 배치 실행 시점을 정리한다.
스케쥴러 빈은 `scheduler.enabled=true`(`SCHEDULER_ENABLED`)일 때만 등록되며 프로세스별로 갈린다 — 매매(TradingOpen/Close)·캘린더 스케쥴러는 `:trading-core`라 `kista-trading`에서, 나머지(KB Land·시세·finance·토큰 정리)는 root `app.jar`의 `kista-scheduler`에서 실행된다.
`kista-api`는 `scheduler.enabled=false`라 어느 스케쥴러도 등록하지 않는다(`deploy/server/docker-compose.yml`).
모든 시각은 `KST` 기준이다.

## 거래 관련

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 장 개시 스케쥴러 | 월~금 22:00 KST | 2h | 개장용 주문 생성 및 선접수 (KIS DCA MOO는 개장 전 접수, 비DST는 개장 23:30까지 대기) |
| 마감 매매 스케쥴러 | 실제 마감 30분 전 1회 예약 (정규장 DST 04:30·비DST 05:30, 조기 마감일 02:30/03:30 등) | 3h | 마감용 주문 생성 및 실행. cron이 아니라 `TaskScheduler` 1회 예약 — 개장 스케쥴러(22:00)가 다음 거래일분을 등록하고 기동 시 `TradingBatchResumer`가 재등록. 마감 시각은 `trading_ref.us_market_sessions`(Alpaca `close`). 휴장일은 22:00 개장 스케쥴러가 개장·마감 heartbeat를 대신 핑 |
| 마감 예약 보정 (`TradingCloseScheduler#reconcile`) | 매시 05분 KST | — | 오늘·내일 마감 예약 누락 재등록, 캘린더 갱신으로 마감 시각이 바뀐 예약 취소 후 재등록 (지난 예약 시각은 조용히 건너뜀) |
| 코인 DCA 스케쥴러 (`kista-trading`) | 10분마다(`0 */10 * * * *`) KST | 9m | 업비트 코인 DCA 주문 동기화·회차 실행 (24/7) |
| 업비트 API 키 만료 점검 (`kista-trading`) | 매일 10:00 KST | 30m | 만료 30·7·0일 전 알림 이벤트 발행 |

## 시세 및 지수

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 공포탐욕지수 수집 스케쥴러 | 매일 07:00 KST, 12:00 KST | 30m | CNN Fear & Greed 지수 수집/저장 — 07:00 미국 장 마감 확정값, 12:00 실패 대비 재수집 (크립토는 수집 없이 조회 시 alternative.me 직접 호출) |
| 벤치마크 ETF 지수 종가 동기화 스케쥴러 | 매일 09:00 KST | 30m | ETF/지수 종가 저장 |

## 캘린더

| 작업 | 실행 시점 | 락 TTL | 용도 |
|---|---:|---:|---|
| 기동 시 캘린더 초기 적재 | 앱 기동 직후 | 1h | 당해 연도 포함 3년치 + 기존 휴장일 보유 연도 중 휴장일·세션(`us_market_sessions`, 연 전체 일자)이 빠진 연도 적재 |
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

## 거래일 경계: 실제 마감 30분 전 (2026-11-11 변경)

거래일 경계는 "그날 거래일 주문을 마감 배치가 넘겨받는 시각"이다. 조기 마감일(13:00 ET 마감, KST 02:00/03:00)을 맞추려고 고정 04:30에서 **그 거래일 실제 마감 30분 전**으로 바꿨다(`SessionTradeDates.nextTradeDate` — 미리보기·수동 실행·취소·인출·VR 재설정·DCA 다음 실행일 공용). 휴장일은 기존 `KstTradeDates`(04:30) 경계로 폴백한다.

- 예외: 개장 배치 재개 판정(`TradingOpenScheduler.resume`·`TradingBatchResumer.resumeOpen`)과 `TradingService.placeOpenOrders`는 04:30 경계를 유지한다 — 대신 실제 마감 30분 전을 넘기면 개장 재개를 건너뛴다.
- 기존 결정의 손해(마감 30분 전~마감 동안 다음 거래일 계획 표시)는 그대로다. 일시정지 취소는 오늘·다음 거래일을 모두 처리해 영향이 없다.
