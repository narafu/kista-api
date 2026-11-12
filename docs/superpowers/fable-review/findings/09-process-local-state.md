# 09. 프로세스 로컬 상태 전수 조사 (web N + worker 1 합치기 관문)

조사 기준: 2026-11-12 main. 읽기 전용 조사(코드 변경 없음). 경로는 `trading-core/src/main/java/com/kista/tradingcore/` 를 `TC/`, `shared/src/main/java/com/kista/shared/` 를 `SH/`, `src/main/java/com/kista/api/` 를 `API/` 로 줄여 쓴다.
전제: 합친 뒤 같은 trading 코드가 web 여러 개(HTTP 요청 경로) + worker 1개(@Scheduled·배치·재개)에서 동시에 돈다.

## A. JVM 안 상태를 가진 빈

### A-1. 브로커 호출·토큰 계통

| # | 클래스 file:line | 담는 것 | 쓰는 쪽 / 읽는 쪽 | web N + worker 1에서 깨지는 것 | 심각도 |
|---|---|---|---|---|---|
| A1 | `TC/broker/adapter/out/internal/CallIntervalGate.java:16` (`nextSlotByKey`, `System.nanoTime` 슬롯). 인스턴스: `kis/KisHttpClient.java:123`(350ms, 대기 20s 상한, 키=appKey), `meritz/MeritzHttpClient.java:44`(110ms, 20s, 키=appKey) | 키별 다음 호출 가능 시각(JVM 단조 시계) | 두 곳 모두 HTTP 요청 경로(잔고·포트폴리오·미리보기·수동 매매·주문취소)와 배치 경로가 같은 `executeWithRetry`를 탄다 | 간격 제한이 **프로세스별로 따로** 적용된다. 같은 appKey를 worker 배치(병렬 2/계좌)와 web 사용자 조회가 동시에 쏘면 KIS EGW00201(초당 거래건수)·메리츠 한도를 넘긴다. 주문 POST는 `retryOnRateLimit=false`라 재시도 없이 BrokerRateLimitException으로 거절 → **배치 주문 접수 실패**(KisHttpClient.java:112-123, 140-177). `System.nanoTime` 슬롯이라 Redis 공유 시 시계 기준도 바꿔야 한다 | 벤더 차단·주문 안전(접수 실패) |
| A2 | `TC/broker/adapter/out/internal/DbTokenCoordinator.java:21` (`locks` ConcurrentMap<UUID,ReentrantLock>), KIS·메리츠 계좌 토큰 | 계좌별 JVM 락 + DB `broker_tokens` 캐시 | `KisAuthApi.getToken/recoverToken`(KisAuthApi.java:62-72) — 요청·배치 양쪽 | 토큰 만료 시점에 두 프로세스가 동시에 miss → 각자 `/oauth2/tokenP` 발급. KIS는 **1분당 1회 제한(EGW00133)** 이라 한쪽이 `BrokerRateLimitException`/`KisApiException`으로 실패(송신 전 실패로 확정 거절 처리됨, KisHttpClient.java:135-139). 클래스 주석은 "재발급이 비파괴적이라 인스턴스 간 조정 불필요"(DbTokenCoordinator.java:13-16)라 했지만 1분 제한은 고려하지 않았다. 401 복구(`recover`)도 `invalidateToken` 후 재발급이라 서로 상대 토큰을 지울 수 있다 | 주문 안전(낮음~중간, 일시 실패) |
| A3 | `TC/broker/adapter/out/kis/KisAdminTokenCoordinator.java:31,33` (`lock`, `volatile memory`) | KIS 관리자 시세 토큰: Redis `kis:admin:token:{sha8}` + JVM 메모리 폴백 | 시세 조회(요청·배치 공통) | Redis가 주 저장이라 프로세스 간 공유됨. 단 `lock`이 JVM 락이라 Redis miss 시점에 두 프로세스가 동시 발급 가능(EGW00133). 메모리 폴백은 Redis 장애 때만 | 벤더 차단(낮음) |
| A4 | `TC/broker/adapter/out/kis/KisAuthApi.java:46` (`tempTokenCache`, 90초) | 연결 테스트 직후 계좌번호 검증용 임시 토큰(appKey 키) | 계좌 등록 `verifyCredentials`→`verifyAccount`(HTTP 요청 경로) | 두 호출이 서로 다른 web 인스턴스로 가면 캐시 미스 → 재발급 → EGW00133으로 계좌 등록 실패. blue/green·LB 환경에서 실제로 일어남 | 화면 지연(등록 재시도로 해소) |
| A5 | `TC/broker/adapter/out/toss/TossDistributedTokenCoordinator.java` + `TossRedisTokenStore` | Redis lease + fencing, 계좌·admin 토큰 | 요청·배치 공통 | **이미 분산 대응됨**(Redis canonical). JVM 상태 없음 | 무해 |
| A6 | `TC/broker/adapter/out/toss/UsdKrwRateCache.java:19` (volatile Entry, 60s) | USD/KRW 환율 | Toss 잔고·평가 경로 | 프로세스마다 별도 캐시 → 호출 수가 프로세스 수만큼 늘 뿐. 값 불일치는 60초 이내 | 무해 |
| A7 | `TC/broker/adapter/out/toss/TossPriceApi.java:48` (`prevCloseCache`, 키=종목+거래일+버킷, 만료 없음) | 전일종가 | 시세 조회(요청·배치) | 프로세스마다 따로 채움. 버킷(정규장 진행 여부)이 키라 값은 같아진다 → 호출 수 증가뿐 | 무해 |
| A8 | `TC/broker/adapter/out/toss/TossCandleApi.java:43` (`TtlResultCache latestDailyCache`, 2분) | 종목별 최신 일봉 600봉 | 시세·통계(요청) · DCA 입력(배치) | 호출 수 증가, 값은 2분 내 차이 | 무해 |

### A-2. trading 실행·통계·알림 계통

| # | 클래스 file:line | 담는 것 | 쓰는 쪽 / 읽는 쪽 | web N + worker 1에서 깨지는 것 | 심각도 |
|---|---|---|---|---|---|
| A9 | `TC/trading/application/service/AccountBudgetLock.java:17` (`locks` 계좌별 ReentrantLock). 사용처: `TradingCandidatePlanner.java:257`(배치 승인+저장), `ManualTradingService.java:109`(수동 실행 승인+저장), `TradingOrderExecutor.java:122`(접수 직전 재캡) | 계좌별 "예약 합계 → 원장 잔여 → PLANNED 커밋" 임계 구역의 상호 배제 | 배치(worker)와 수동 실행(web HTTP)이 같은 락을 쓴다. **프로세스 안 락뿐**이다. 클래스 주석이 스스로 "JVM 내 락 — 단일 인스턴스 전제, 다중 인스턴스가 되면 DB 락으로 승격"이라고 적었다(AccountBudgetLock.java:13) | web의 수동 실행과 worker의 배치가 같은 계좌에서 동시에 돌면 (1) 같은 사이클에 PLANNED가 이중 저장되고(`orders`에 (사이클, 거래일, 슬롯) 유일 제약이 없다 — `migration-trading` 전체에 orders 유니크 없음), (2) 같은 원장 여유분을 두 번 차감해 **원장 상한 초과 매수**가 가능하다. 단일 JVM이라 지금은 막히던 경합 | **주문 안전·돈 (최우선)** |
| A10 | `TC/trading/application/service/support/TradingBatchRunState.java:15-19` (단일 슬롯 `batchThread`, `critical`, `volatile stopping`), `TradingBatchShutdownCoordinator.java:21`(SmartLifecycle), `adapter/in/schedule/TradingBatchSerialExecutor.java:12`(ReentrantLock) | 실행 중 배치 스레드·임계구역·종료 요청, 개장·마감 직렬화 | 쓰는 쪽은 `TradingService.java:76,142,226,319,366` 배치 경로뿐. 수동 실행·미리보기는 쓰지 않는다 | worker 안에서만 의미가 있다. web에 올려도 `requestStop`은 `batchThread==null`이라 즉시 true → 무해하지만 의미도 없다. **web 종료 시 진행 중 수동 접수(`placeAtOpenOrders`)는 임계구역 보호가 없다**(지금도 같음) — blue/green이면 오히려 안전해짐 | 무해(worker 전용으로 한정) |
| A11 | `TC/trading/adapter/in/schedule/TradingCloseScheduler.java:53` (`reservations` HashMap) + `:91` `synchronized register` | 거래일별 마감 접수 예약(`TaskScheduler` ScheduledFuture) | `schedule()`(개장 스케쥴러)·`reconcile()`(매시 :05)·재개가 쓴다 | 이 빈이 web에도 떠 있으면 **프로세스마다 예약이 등록되어 각자 발화**한다. DB `SchedulerLockService.tryRun`이 막아 주지만 그 락이 실패하면 "[마감 예약 누락] 락 획득 실패" 관리자 알림이 web 수만큼 발생(`:128-134`) | 알림 노이즈. 단 worker 전용이면 무해 |
| A12 | `TC/stats/application/service/TradingStatsService.java:58` (`TtlResultCache statsResultCache`, TTL 5분 `:44`), 무효화 `@EventListener` `:105,114` | 사용자별 summary·equity-curve 결과 | 읽기: HTTP `/api/stats/*`. 무효화: `TradingReportReadyEvent`(배치 `TradingReporter.java:85`→worker), `StrategyCapitalChangedEvent`(HTTP: `StrategyService.java:65,84,196`, `CapitalLedgerService.java:106,169`, `StrategyUpdateWriter.java:82`, `VrReconfigureService.java:129`, `ManualTradeCorrectionService.java:116` / worker: `CryptoOrderSynchronizer.java:126`, `AccountCascadeListener.java:37`) | 무효화는 **이벤트를 발행한 프로세스의 캐시만** 비운다. (1) worker 배치가 체결·스냅샷을 저장해도 web 캐시는 최대 5분 낡은 값. 지금은 `HIGHEST_PRECEDENCE`로 SSE 발행보다 먼저 비워 "UI 재조회가 stale을 받지 않게" 설계했는데(`:101-104`) 그 보장이 깨진다. (2) 투자금 추가·인출·VR 재설정을 web-A가 처리하면 web-B 캐시는 5분간 낡음 → **내 변경이 안 보이는 화면** | 화면 지연(돈 판단에 쓰는 원금·손익 표시 지연이라 중간) |
| A13 | `TC/stats/application/service/CryptoMarketIndicatorService.java:67` (`TtlResultCache`, 10분/1분 TTL), `TC/broker/adapter/out/toss/TossCandleApi.java:43` | 공개 지표·시세 | 요청 경로만 | 프로세스별 중복 외부 호출 | 무해 |
| A14 | `TC/trading/application/service/DcaPlanInputCollector.java:65,67,70` (`closeCache`, `fearGreedMemo`, `previousOpenCache`) | DCA 일봉 종가·공포탐욕 메모·직전 개장일(세션 키, 지난 세션 키 제거) | 배치 + 미리보기(`StrategyOrderPlanBuilder.java:45` 경유 web) | 세션 단위 캐시라 프로세스별로 따로 채워도 값이 같다. 단 web에서 세션 초반에 채운 값이 세션 내내 고정(지금 단일 JVM에서도 같음) | 무해 |
| A15 | `TC/trading/application/service/OrderSubmissionReconciler.java:44` (`alerted` Set) | 이미 알린 SUBMITTING 잔류 주문 id | `placeAtOpenOrders`(`TradingOrderExecutor.java:59`)가 호출 → 배치와 web 수동 실행 양쪽 | 알림 중복 제거가 프로세스별 → 같은 잔류 주문 알림이 프로세스 수만큼 나감. `resend`는 Toss 멱등키·행 잠금(`OrderPersistenceAdapter.java:120-131`)·`IN_FLIGHT_GRACE 2분`(`:46`)이 있어 이중 접수는 막힘 | 알림 노이즈 |
| A16 | `TC/trading/application/service/CryptoDcaRunner.java:50` (`lastReported`) | 대상별 하루 1회 알림 | 코인 DCA 틱(worker 전용) | worker 1개라 무해. 재기동 시 1회 재알림(주석에 이미 명시) | 무해 |
| A17 | `TC/trading/application/service/support/TradingParallelRunner.java:37` (호출 단위 지역 `Semaphore` 맵), `MarketEventNotifier.java:44` | 배치 계좌별 동시 2개 상한 | 배치 호출 안에서만 생존 | **프로세스 전역 상한이 아니다.** 계좌당 동시 2개는 worker 안에서만 보장되고 web 호출은 그 상한 밖(A1 참조) | A1에 흡수 |
| A18 | `SH/platform/cache/TtlResultCache.java:23-25` (`cache`, 스트라이프 락, `generation`) | single-flight + TTL + 무효화 세대 | 사용처: `TradingStatsService`, `CryptoMarketIndicatorService`, `TossCandleApi`, root `BenchmarkService.java:35`, root `FearGreedQueryService.java:40` | 프로세스 로컬 캐시 공통 골격. 무효화가 프로세스 경계를 못 넘는다는 점이 A12의 원인 | A12에 흡수 |
| A19 | `SH/platform/redis/RedisStreamSubscriber.java:73,157` (`synchronized start/stop`, `container`, `subscription`) | 스트림 구독 컨테이너 | trading-core 3개·root 2개 컨슈머 | 컨슈머 이름이 `접두사-UUID`라(`:51`) 인스턴스가 늘어도 그룹 안에서 경쟁 소비로 동작. 복구 컨슈머 이름 `{접두사}-recovery`는 인스턴스 공통이라 XCLAIM 대상이 같아 충돌은 없음 | 무해(합친 뒤 대부분 삭제 대상) |

### A-3. 조회 대상 중 JVM 상태가 **없는** 것 (확인 결과)

| 대상 | 확인 | 결론 |
|---|---|---|
| `RuntimeSettings`류 | trading 정책 `TC/trading/adapter/out/persistence/policy/TradingPolicySettingsPersistenceAdapter`, root `API/admin/adapter/out/persistence/settings/RuntimeSettingsPersistenceAdapter` 모두 필드 캐시 없음(grep `cache|volatile|Atomic` 0건). 매 호출 DB 조회 | 무해 |
| 토스 토큰 | `TossDistributedTokenCoordinator`(Redis lease + fencing, `:24-30`), `TossRedisTokenStore` | 이미 다중 프로세스 대응 |
| Upbit·Binance 어댑터 | 간격 게이트·토큰 캐시 없음(`UpbitHttpClient.java:99`, `BinanceHttpClient.java:121`는 429/418을 `BrokerRateLimitException`으로 변환만) | 한도는 IP·키 단위라 프로세스 수와 무관하게 같은 서버 IP를 공유. 간격 제어가 원래 없음. 418(밴) 위험은 현재와 동일 |
| root SSE | `API/notify/adapter/out/sse/TradeSseEmitterRegistry.java:23`, `SseEmitterRegistry.java:20` (`ConcurrentHashMap<UUID, SseEmitter>`) | emitter는 연결을 받은 web 인스턴스에만 있다. 체결 알림은 Redis Pub/Sub `trade.event`를 **모든 인스턴스가 구독**(`RedisTradeEventSubscriber.java:28`)해 해결됨. 단 `SseEmitterRegistry`(사용자 상태 SSE)는 `UserApprovedEvent` AFTER_COMMIT 리스너(`:43,48`)가 **이벤트를 발행한 인스턴스의 emitter만** 건드려, 승인 처리를 받은 인스턴스와 사용자 연결 인스턴스가 다르면 상태 푸시가 사라진다(web 다중 인스턴스에서 새로 드러남, 재조회로 수렴) |

## B. 락 전수

| # | 종류 | 위치 | 보호 대상 | 프로세스 간 충분한가 |
|---|---|---|---|---|
| B1 | **JVM** | `TC/trading/application/service/AccountBudgetLock.java:17` | 계좌별 원장 판정+PLANNED 저장, 재캡 | **불충분 — 최우선.** A9. 임계 구역 본문(`TradingCandidatePlanner.allocateAndSave:295`, `ManualTradingService.java:109-125`, `BuyOrderPriceCapper.capIfNeeded`)은 브로커 호출이 없고 DB만 쓴다(BuyOrderPriceCapper·allocateAndSave에 broker 포트 import 없음). 그래서 DB 기반 락으로 바꾸는 것이 가능하다(PG advisory lock 또는 `accounts` 행 `FOR UPDATE`). 본문이 여러 짧은 트랜잭션으로 쪼개져 있어(`OrderPort` 어댑터 각각 `@Transactional`) 바깥 트랜잭션 하나로 감싸면 의미가 바뀌니, 세션 단위 advisory lock(전용 커넥션)이나 Redis 락이 후보 |
| B2 | **JVM** | `TC/broker/adapter/out/internal/DbTokenCoordinator.java:21,38` | KIS·메리츠 계좌 토큰 동시 발급 | **불충분(중간).** 토큰 DB 캐시(`broker_tokens`)가 있어 만료 순간 경합만 문제. 두 프로세스가 동시에 `/oauth2/tokenP`를 부르면 KIS 1분 1회 제한(EGW00133, `KisAuthApi.java:45,102`)으로 한쪽이 실패. 실패는 송신 전 확정 거절이라 주문이 안 나가는 쪽이다. 401 복구 `invalidateToken`이 서로 상대 토큰을 지울 수 있음 |
| B3 | **JVM** | `TC/broker/adapter/out/kis/KisAdminTokenCoordinator.java:31` | KIS 관리자 시세 토큰 발급 | 부분. Redis 캐시가 주(`:36-41`)라 공유되지만 발급 직렬화는 JVM 락. 만료 순간 이중 발급 가능(낮음) |
| B4 | **JVM** | `TC/broker/adapter/out/internal/CallIntervalGate.java:16` | 앱키별 호출 간격 | **불충분.** A1 |
| B5 | **JVM** | `TC/trading/adapter/in/schedule/TradingBatchSerialExecutor.java:12`, `TradingBatchRunState.java:15` | 개장·마감 배치 직렬화, 종료 보호 슬롯 | worker 전용이면 충분. 배치 간 상호 배제는 DB `SchedulerLockService`(B7)가 별도로 담당 |
| B6 | **JVM** | `TradingCloseScheduler.java:91` `synchronized register`, `UsdKrwRateCache.java:32`, `TtlResultCache` 스트라이프, `RedisStreamSubscriber` | 예약 등록, 캐시 single-flight, 구독 컨테이너 | 프로세스 안 정합성용. 크로스 프로세스 보호 불필요 |
| B7 | **DB(행 삽입 락)** | `SH/platform/scheduling/SchedulerLockService.java:58-72,74-90` (`scheduler_locks` INSERT … ON CONFLICT … WHERE lock_until <= now()) | 배치 단위 락(`TradingBatchJob.OPEN/CLOSE.lockName()`, `crypto-dca`, 캘린더 갱신, 키 만료 알림 등) | 배치끼리는 충분. **`takeOver`(forceAcquire `:74`)는 "단일 인스턴스·비겹침 배포 전제"라고 명시(`:30-31`)** — web에서 호출하면 안 된다(worker 전용). 성공 시 락을 TTL까지 유지(`:41`)하므로 수동 트리거(`TradingCloseScheduler.runNow:139`)가 web에서 먼저 돌면 같은 날 자동 배치가 락 실패로 건너뛰어짐 → worker 전용으로 한정해야 안전. 락 테이블이 root(`public.scheduler_locks`)·trading(`trading.scheduler_locks`) 두 벌이고 비정규화된 `scheduler_locks`는 search_path로 해석됨(`trading-core application.yml:19`, `src/main/resources/application.yml:19`) → 한 DataSource로 합치면 해석이 하나로 정해져야 한다 |
| B8 | **DB(행 락)** | `TC/trading/adapter/out/persistence/StrategyCycleJpaRepository.java:19` `@Lock PESSIMISTIC_WRITE`(→`StrategyCyclePort.lockForUpdate`) | 사이클 단위 원장 직렬화. 사용처: `BuyOrderPriceCapper.java:71`, `CapitalLedgerService.java:200`(투자금 추가·인출), `StrategyUpdateWriter.java:106`(시드 수정), `ManualTradeCorrectionService.java:72`, `CryptoLedgerRecorder.java:38` | 충분(같은 사이클 단위). 단 B1(계좌 단위)은 이걸로 대체되지 않는다 — 같은 계좌의 서로 다른 사이클·전략이 같은 예수금을 공유하기 때문 |
| B9 | **DB(행 락)** | `TC/trading/adapter/out/persistence/OrderPersistenceAdapter.java:120-131` `lockOrConflict`(`refresh(PESSIMISTIC_WRITE)`), 사용 `markSubmitting:57`, `markPlaced:94`, `cancelPlanned:107`, `:271` | 주문 상태 전이(PLANNED→SUBMITTING→PLACED, 취소) | **충분.** web 주문 취소와 worker 접수가 경합하면 `OrderStateConflictException`으로 한쪽이 지고 HTTP를 보내지 않는다. 이 한 줄이 "이중 접수"를 막는 가장 강한 장치 |
| B10 | **Redis** | `TossRedisTokenStore`(lease + fencing generation CAS) | Toss 토큰 발급 | 충분 |
| B11 | **락 없음(점검만)** | `VrReconfigureService.reconfigure:64-135`(트랜잭션·락 없음, 읽기→취소→쓰기), `StrategyService` 상태 변경, `OrderCancelService.cancelByCycle` | 사용자 재설정 vs 같은 시각 `VrCycleRolloverService`·배치 | **지금도 같은 JVM 안에서 JVM 락도 DB 락도 없는 경합**이라 합쳐도 악화되지 않는다. 별건 후보 |

사용자 요청 × 배치 매트릭스(다른 프로세스에서 동시에 돌 때)

| 사용자 요청(web) | 배치(worker)와 겹치면 | 보호 장치 | 판정 |
|---|---|---|---|
| 수동 매매 `POST /api/trading-cycles/{id}/execute` (`TradingCycleController.java:191`) | 개장·마감 후보 계획 저장(`TradingCandidatePlanner`) | B1 JVM 락만 | **깨짐** |
| 주문 취소 `DELETE /api/orders/{id}`, `DELETE …/execute` | 배치 접수 | B9 행 락 | 안전 |
| 투자금 추가·인출 | 배치 원장 사용 | B8 사이클 행 락(`CapitalLedgerService:200`) | 안전(추가로 `StrategyCapitalChangedEvent`는 A12) |
| 전략 시드 수정 | 배치 | B8(`StrategyUpdateWriter:106`) | 안전 |
| VR 재설정 | `VrCycleRolloverService` | 없음(B11) | 현행 동일 |
| 전략 일시정지·재개·삭제 | 배치의 전략 목록 스냅샷 | 없음. 배치가 목록 조회 후 접수 직전까지 상태를 재확인하는지는 이번 조사 범위 밖 | 현행 동일 |
| 계좌 키 교체·삭제 | 배치의 브로커 호출 | 토큰 evict(`AccountTokenEvictionPort`, KIS DB·Toss Redis)로 공유 저장소 경유 | 안전 |

## C. 이벤트 리스너 전수

공통 사실: 리스너는 **발행한 프로세스에서** 돈다. EPR(`event_publication`)은 트랜잭션 안에서 발행된 `@TransactionalEventListener`(AFTER_COMMIT)만 기록한다. `fallbackExecution=true`는 트랜잭션 밖 발행이면 즉시 동기 실행(EPR 기록 없음). 재발행 설정:
- root `src/main/resources/application.yml:46` 기본 `true`, prod `application-prod.yml:18`은 `false`(kista-api role), kista-scheduler role이 env로 `true`. 즉 **root는 "재발행 소유자 = scheduler role 단독"**.
- trading-core `application.yml`에는 키가 없고 compose env `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"`를 kista-trading에만 건다(`.github/tests/compose-invariants.bats:16`).
- EPR 저장소가 둘: `public.event_publication`(root)·`trading.event_publication`(`trading-core application.yml:41`). 합치면 한쪽으로 통일하거나 둘을 한 DataSource에서 구분할 방법이 필요하다(Modulith는 `spring.modulith.events.jdbc.schema` 하나만 받는다).
- 이벤트·리스너 FQCN을 옮기면 미완료 row가 고아가 된다 → `deploy/hooks/pre-apply.sh`·`epr-orphans.sh`·`docker-infra.md` "배포 직전 EPR 정리 런북" 대상. **모듈 합치기·개명은 모두 이 런북을 탄다**(예: `TradingUserCascadeListener.java` 주석이 직접 경고).

### C-1. trading-core 리스너

| 리스너 file:line | 듣는 이벤트 | 발행 지점 | 하는 일 | 발행 프로세스에서 돌아도 되는가 |
|---|---|---|---|---|
| `TC/stats/.../TradingStatsService.java:105` `@EventListener` | `TradingReportReadyEvent` | 배치 `TradingReporter.java:85` | 메모리 캐시 무효화 | **아니오(A12)** — worker에서만 돌아 web 캐시를 못 비운다 |
| `TradingStatsService.java:114` `@EventListener` | `StrategyCapitalChangedEvent` | HTTP 6곳 + worker 2곳(A12) | 같음(afterCommit 동기화) | **아니오(A12)** |
| `TC/notify/.../TradingReportNotifier.java:34` `@TransactionalEventListener(fallbackExecution)` | `TradingReportReadyEvent` | 배치(worker) | 텔레그램/FCM 리포트 + Redis `trade.event` Pub/Sub(`RedisTradeEventPublisher`) | worker가 발행하므로 worker에서 실행. 외부 호출 포함 → EPR 재시도 대상이면 재발행 소유자가 worker여야 함 |
| `TC/notify/.../TradingAlertNotifier.java:36,46,57,62,67,72,77,83,89` | `TradingErrorEvent`, `InsufficientBalanceEvent`, `MarketClose/Open/CloseEvent`, `DcaRoundSkippedEvent`, `BatchInterruptedEvent`, `StrategyAutoPausedEvent`, `CycleRotationFailedEvent` | 대부분 worker(배치). `TradingErrorEvent`는 `ManualTradingService.java:167`, `OrderSubmitter`(수동 접수도 사용) 등 **web 경로에서도 발행** | 텔레그램 발송(관리자·사용자) | web에서 돌아도 기능상 가능(텔레그램 토큰 env가 web에도 필요). 중복 아님 |
| `TC/notify/.../CycleEndedNotifier.java:23`, `CycleLifecycleNotifier.java:23,30`, `CryptoTradeNotifier.java:27`, `OrderCancelFailureNotifier.java:19`, `ApiKeyExpiryNotifier.java:18`, `PrivacyAlertNotifier.java:18` | `CycleEndedEvent`(수동 체결 보정 = **web** `ManualTradeCorrectionService.java:112`), `CycleCompleted/NewCycleStarted`(배치 + VR 재설정 web `VrReconfigureService.java:140`), 코인 체결(worker), 주문 취소 실패(web·worker), 키 만료(worker), PRIVACY(web·worker) | 텔레그램·FCM 알림 | web/worker 어디서 돌아도 동작. EPR 재시도는 발행 프로세스가 아니라 재발행 소유자(worker)가 한다 → 알림 리스너가 web 환경변수 없이도 worker에서 실행되도록 **양쪽 role에 동일 시크릿이 있어야 함** |
| `TC/trading/adapter/out/redis/AppErrorStreamPublisher.java:26` `@EventListener` | `AppErrorRaisedEvent`(shared) | 예외 처리기 `TradingExceptionHandler.java:115`(web), `TradingNotifyAdapter.java:33` | Redis Stream `stream:app.error`로 push → root `AppErrorStreamConsumer`가 `app_error_logs` 저장 | 합친 뒤에는 **root `AppErrorRaisedListener`(`API/admin/adapter/in/event/AppErrorRaisedListener.java:20` `@EventListener`)가 같은 이벤트 클래스를 직접 듣는다 → 한 오류가 DB에 직접 1행 + 스트림 경유 1행으로 이중 기록**(두 리스너 모두 `@EventListener` 동기) |
| `TC/trading/adapter/in/event/TradingUserCascadeListener.java:25` `@TransactionalEventListener(AFTER_COMMIT, fallback)` | `UserDeletedEvent`(shared.kernel) | 지금은 Redis `stream:user.deleted` 컨슈머가 `UserEventRepublisher.java:19-28`로 **로컬 재발행** | 사용자 매매 데이터 정리: 열린 주문 증권사 취소(`cancelOpenOrders`)·삭제 | 합치면 root `UserEventStreamPublisher`가 같은 이벤트를 **직접도 받으므로** 이중 실행 + 아래 루프 위험 |
| `TC/trading/adapter/in/event/UserNotifyProfileSyncListener.java:27` | `UserNotifyProfileChangedEvent` | 위와 같은 경로 | `user_notify_profile` 복제본 upsert(occurredAt 가드) | 같은 문제. 복제본 자체가 사라질 대상 |
| `TC/trading/adapter/in/event/AccountCascadeListener.java:29` | `AccountDeletedEvent`(trading 소유) | `AccountDeletionWriter.java:29`(web) | 전략·사이클 정리 후 `StrategyCapitalChangedEvent` 재발행 | web에서 실행돼도 가능 |
| `TC/trading/adapter/in/schedule/TradingBatchResumer.java:45` `@EventListener(ApplicationReadyEvent)` | 기동 | — | 거래일 미완료 배치 재개(`takeOver`) | **web에서 돌면 안 됨(E)** |
| `TC/marketcalendar/.../MarketCalendarRefreshScheduler.java:37` `@EventListener(ApplicationReadyEvent)` | 기동 | — | 캘린더 적재(DB 쓰기) | worker만 |

**루프 위험 확인(합친 직후 단계 1에서 발생)**: root `UserEventStreamPublisher.java:29,35`는 `UserDeletedEvent`/`UserNotifyProfileChangedEvent`를 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`로 받아 XADD한다. trading-core `UserEventRepublisher.java:19-28`은 스트림에서 읽은 같은 클래스 이벤트를 `publishEvent`로 다시 낸다. 한 JVM에 합치면 (발행 → XADD → 컨슈머 → 재발행 → 다시 리스너가 XADD …) **무한 순환**이 된다. 같은 컨텍스트에 두기 전에 한쪽을 끄거나(role/프로파일 가드) 스트림 경로를 먼저 제거해야 한다.

### C-2. root 리스너 (비교)

| 리스너 | 이벤트 | 하는 일 | 합친 뒤 |
|---|---|---|---|
| `API/notify/adapter/out/sse/SseEmitterRegistry.java:43,48` | `UserApproved/RejectedEvent` | 로컬 emitter에 SSE | A-3 참조: 인스턴스 경계에서 유실 |
| `API/notify/adapter/out/gateway/*Notifier.java`, `CompositeUserNotificationAdapter.java:29-52` | 사용자 가입·승인·반려·재신청, 가계부 알림, 스케줄러 생명주기, 공포탐욕 실패 | 텔레그램·FCM | web 발행 → web 실행. 외부 호출 포함이라 AFTER_COMMIT(EPR) 구조 유지 |
| `API/user/adapter/out/redis/UserEventStreamPublisher.java:29,35` | `UserDeleted/UserNotifyProfileChangedEvent` | Redis XADD | 위 루프 위험. 합친 뒤 삭제 대상 |
| `API/admin/adapter/in/event/AppErrorRaisedListener.java:20` | `AppErrorRaisedEvent` | `app_error_logs` 저장 | trading 쪽 `AppErrorStreamPublisher`와 이중 기록(C-1) |
| `API/finance/adapter/in/event/FinanceUserCascadeListener.java:21`, `API/notify/.../UserFcmCleanupListener.java:21`, `UserDeletedNotifier.java:17`, `API/user/adapter/in/event/ApprovalRequirementDisabledListener.java:24`, `API/admin/adapter/in/event/UserSeedPromotedAuditListener.java:24` | 사용자 삭제·승인 정책·시드 승격 | DB 정리·알림 | 변화 없음 |

**다중 web 인스턴스에서 EPR 중복 재발행?** 불가능하게 만들려면 `republish-outstanding-events-on-restart=true`를 worker 하나만 갖는 현재 root 규칙을 그대로 쓰면 된다(`application-prod.yml:13-18`의 "양쪽 true면 미완료 row를 둘 다 claim → 중복 알림" 주석이 같은 사실). 주기 재발행은 `UserEventResubmitScheduler`(root `API/web/UserEventResubmitScheduler.java:37`, trading `TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java:36`)만 있고 둘 다 `scheduler.enabled` 게이트이며 락이 없다(단일 소유자 전제). web에서 FAILED가 된 이벤트는 worker 재기동 또는 이 스케줄러(사용자 이벤트만)로만 재시도된다 — 현재도 같은 구조.

## D. 사용자 요청이 브로커를 호출하는 경로

게이트·토큰 열 기준: **G** = `KisHttpClient.executeWithRetry`/`MeritzHttpClient`의 `CallIntervalGate`(A1) 경유, **T** = 토큰 코디네이터(KIS·메리츠 `DbTokenCoordinator`, Toss Redis) 경유. Toss·Upbit·Binance는 간격 게이트가 없다. 서비스 열은 포트 주입 기준(컨트롤러 → 서비스는 이름·시그니처로 확인).

| 엔드포인트 | 컨트롤러 file:line | 서비스 → 브로커 포트 | G | T | 비고 |
|---|---|---|---|---|---|
| 연결 테스트 `POST /api/accounts/connection-tests` | `TC/account/adapter/in/web/AccountController.java:115` | `AccountService` → `BrokerConnectionTesters` → `KisAuthApi.verifyCredentials`(OAuth 직접 발급) | 아니오 | 아니오(`tempTokenCache` JVM-local, A4) | KIS는 EGW00133 위험, 다음 요청이 다른 web으로 가면 재발급 |
| 계좌 등록 `POST /api/accounts` / 키 교체 `PUT /api/accounts/{id}/credentials` | `AccountController.java:47,83` | `AccountService` → 연결 테스트 + `verifyAccount`(`KisAuthApi.java:114-`, `kisRestClient` **직접 호출 → 게이트 우회**) + 토큰 evict(`AccountTokenEvictionPort`) | 아니오 | 일부 | 등록·교체 순간 1회성. 한도 영향 작음 |
| 계좌 삭제 `DELETE /api/accounts/{id}` | `AccountController.java:100` | `AccountDeletionWriter` → 토큰 evict | - | evict만 | KIS는 DB, Toss는 Redis라 공유 저장소 경유 → 다중 프로세스 안전 |
| 잔고·포트폴리오 `GET /api/accounts/{id}/portfolio` | `TC/stats/adapter/in/web/StatisticsController.java:38` | `AccountStatisticsService.getPresentBalance:45` → `PortfolioPort` | 예 | 예 | A1 핵심 경합 경로 |
| 전일종가·시세 `GET /api/accounts/{id}/prices` | `StatisticsController.java:77` | `AccountStatisticsService.getPrices:93` → `BrokerPricePort.getPrevCloses` | 예 | 예 | Toss는 `prevCloseCache`(A7) |
| 일별 체결 `GET /daily-trades` | `StatisticsController.java:53`, `DashboardController.java:53` | `AccountStatisticsService.getDailyTransactions:52`(DB `orderPort`만) | - | - | **브로커 호출 없음** |
| 미리보기 `GET /api/trading-cycles/{id}/preview`, `GET /api/accounts/{id}/trading-cycles/previews` | `TradingCycleController.java:209,218` | `TradingPreviewService` → `StrategyOrderPlanBuilder`·`TradingPriceFetcher`·`MarginPort`·`SellableQuantityPort` | 예 | 예 | 배치와 같은 계좌를 동시에 여러 건 조회(계좌별 병렬 상한 2는 worker 내부에서만 보장, A17) |
| 전략 등록 `POST /api/accounts/{id}/trading-cycles` / 시드 미리보기 `GET …/strategy-seed-preview` / 시드 수정 | `TradingCycleController.java:72,228,95` | `StrategyCreationService.java:124,159`, `InfiniteMinSeedGuard`, `StrategyHistoryQueryService.java:78` → `BrokerPricePort.getPrevClose` | 예 | 예 | 트랜잭션 밖 호출(`StrategyService.java:62,232`) |
| 투자금 인출·추가 `POST …/capital-withdrawal|capital-injection` | `TradingCycleController.java:118,129` | `CapitalWithdrawalService`(전일종가 HTTP 후 `CapitalLedgerService.java:111`) | 예 | 예 | |
| VR 재설정 `PUT …/vr-config` | `TradingCycleController.java:107` | `VrReconfigureService.java:56,98` → `BrokerPricePort.getPrice` + `cancelByCycle`(브로커 취소) | 예 | 예 | 락 없음(B11) |
| 수동 매매 `POST …/execute` | `TradingCycleController.java:191` | `ManualTradingService`(`TradingPriceFetcher`, `StrategyOrderPlanBuilder`, 개장 후 `TradingOrderExecutor.placeAtOpenOrders` → `OrderSubmitter` → `BrokerAdapterPort` 접수) | 예(접수 POST는 재시도 없음) | 예 | **주문 접수 경로.** A9·A1이 모두 걸린다 |
| 주문 취소 `DELETE /api/orders/{id}`, `DELETE …/execute` | `TC/trading/adapter/in/web/OrderCancelController.java:25`, `TradingCycleController.java:200` | `OrderCancelService` → `BrokerOrderCorrectionPort.cancel`, `SubmittingOrderAwaiter` | 예 | 예 | 행 락 B9로 접수 경합은 안전 |
| 전략 정지·삭제·아카이브 `PATCH …/pause|archive`, `DELETE …/{id}` | `TradingCycleController.java:161,171,151` | `StrategyService.java:70,89,111`(NOT_SUPPORTED, 미체결 주문 증권사 취소 HTTP 포함) | 예 | 예 | |
| 원장 합계 `GET /api/accounts/{id}/ledger-totals` | `TC/trading/adapter/in/web/AccountLedgerTotalsController.java:30` | `AccountLedgerTotalsService.java:54-66` → `BrokerPricePort`·`MarginPort`·`CryptoExchanges`(`tryGet`) | 예 | 예 | fail-open |
| 코인 포트폴리오·시세 | `TC/stats/adapter/in/web/CryptoPortfolioController.java:38,54` | `CryptoPortfolioService` → `CryptoExchanges.of(broker)` | 아니오(게이트 없음) | 없음 | 업비트·바이낸스 한도는 IP 기준. 합쳐도 같은 서버 IP |
| 공개 코인 지표 | `CryptoMarketIndicatorController.java:30` | `CryptoMarketIndicatorService` → CMC | - | - | 캐시(A13) |
| 백테스트 | `BacktestController.java:37` | `BacktestService` → `HistoricalCandlePort`(Alpaca, 브로커 아님) | - | - | |
| 내부 API(root→trading) 시세·환율 | `CandleInternalController.java:26,34`, `TradingStatsInternalController.java:41` | `CandlePort`·`ExchangeRatePort`(Toss 공용) | - | Toss admin 토큰(Redis) | 합치면 프로세스 안 호출로 대체 |

요약: KIS·메리츠를 타는 사용자 경로는 잔고·시세·미리보기·시드 미리보기·등록·인출·VR 재설정·수동 매매·취소·정지/삭제 전부가 같은 앱키 게이트를 공유한다. **게이트를 우회하는 사용자 경로는 `KisAuthApi.verifyAccount`(등록 1회성) 하나**다.

## E. 기동 시 동작

| 대상 file:line | 동작 | web에서 돌면 | 처리 방향 |
|---|---|---|---|
| `TC/trading/adapter/in/schedule/TradingBatchResumer.java:45` (`@EventListener(ApplicationReadyEvent)`, `@Profile("!local")`, `scheduler.enabled`) | 거래일 미완료 배치 재개, `takeOver`로 잔여 락 인수(`SchedulerLockService.java:30-32` "단일 인스턴스·비겹침 전제") | **금지.** web이 인수하면 worker가 도는 배치 락을 덮어쓴다 | worker 전용 조건 |
| `TC/marketcalendar/.../MarketCalendarRefreshScheduler.java:37` (`ApplicationReadyEvent`, `@Order(HIGHEST)`) | 캘린더 적재 후 `TradingBatchResumer`가 읽음 | web은 읽기만 필요. 쓰기는 중복 | worker 전용 + 순서 `HIGHEST→LOWEST` 유지 |
| `TC/trading/adapter/in/redis/UserEventStreamConsumer.java:39`, `API/notify/adapter/in/redis/PushNotificationStreamConsumer.java:39`, `API/admin/adapter/in/redis/AppErrorStreamConsumer.java:40` (`ApplicationReadyEvent` → `start()`) | Redis Stream 구독 시작 + 5분 복구 `@Scheduled`(게이트 없음) | 경쟁 소비라 기능상 중복은 없음. 단 합친 뒤 스트림 자체가 삭제 대상 | 단계 2에서 삭제. 그 전엔 worker 전용 권장 |
| `API/notify/adapter/out/sse/RedisTradeEventSubscriber.java:28`, `API/benchmark/adapter/in/redis/TradeEventBenchmarkCacheInvalidator.java:27` (`@PostConstruct` → Pub/Sub 구독) | `trade.event` 구독 | **web 전부에서 필요**(SSE emitter·캐시가 web에 있음). worker는 불필요 | web 전용(또는 양쪽 무해) |
| `TC/trading/application/service/support/TradingBatchShutdownCoordinator.java:16` (SmartLifecycle, phase `MAX_VALUE-1`) | 종료 시 배치 임계구역 대기(최대 150s) | 무해하지만 의미 없음 | worker 전용 |
| `SH/platform/PlatformAutoConfiguration`, Flyway(root `public`·trading `trading` 두 이력, `migrate-on-startup`) | 기동 시 마이그레이션 | 둘 다 기동 시 실행 → 동시 기동·롤백 겹침 시 expand/contract 필요 | trading 스키마 마이그레이션도 backward-compat(`docker-infra.md:14`의 root 규칙이 trading에 확장됨) |
| 캐시 워밍·`ApplicationRunner`·`CommandLineRunner`·`InitializingBean` | 저장소 전체 grep 결과 **없음** | - | - |

## F. @Scheduled 전수

`scheduler.enabled`는 `matchIfMissing = true`(코드 기본 켜짐), prod yml이 API role에서 끈다. `@EnableScheduling`은 `ApiApplication.java:12`·`TradingApplication.java:14` 각자 선언.

| 클래스 | cron / 주기 | 게이트 | 분산 락 | worker 전용 |
|---|---|---|---|---|
| `TC/trading/adapter/in/schedule/TradingOpenScheduler.java:46` | `0 0 22 * * MON-FRI` KST | scheduler.enabled | `SchedulerLockService.tryRun` OPEN | 예 |
| `TradingCloseScheduler.java:70,79` + 동적 `TaskScheduler.schedule`(`:111`) | `0 5 * * * *`(예약 보정), `0 30 4 * * TUE-SAT`(하트비트), 거래일별 마감 예약 | scheduler.enabled | `tryRun` CLOSE, 예약은 **메모리**(`:53`) | 예 |
| `TC/trading/adapter/in/schedule/CryptoDcaScheduler.java:27` | `0 */10 * * * *` | scheduler.enabled | `tryRun("crypto-dca", 9m)` | 예 |
| `TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java:36` | fixedDelay 10분 | scheduler.enabled | 없음(단일 소유 전제) | 예(합친 뒤 root 것과 하나로) |
| `TC/trading/adapter/in/redis/UserEventStreamConsumer.java:63` | fixedDelay 5분 | **없음** | 없음 | 삭제 대상 |
| `TC/account/adapter/in/schedule/ApiKeyExpiryScheduler.java:32` | `0 0 10 * * *` | scheduler.enabled | `tryRun` | 예 |
| `TC/marketcalendar/.../MarketCalendarRefreshScheduler.java:53,64` | `0 0 0 1 1 *`, `0 0 1 1 * *` | scheduler.enabled | `tryRun` | 예 |
| `TC/trading/adapter/in/web/TradingSchedulerInternalController.java:21` | HTTP 수동 트리거 | scheduler.enabled | `runNow`의 `tryRun` | **빈이 worker에만 존재 → web에서는 라우팅 필요(E/I)** |
| `API/benchmark/.../KbLandHousingBenchmarkScheduler.java:24`, `MarketIndexPriceSyncScheduler.java:26`, `KbLandPriceIndexScheduler.java:29,34` | 토 08:00 / 매일 09:00 / 토 08:10·매월 1일 08:20 | scheduler.enabled | `tryRun` | 예 |
| `API/user/adapter/in/schedule/RefreshTokenCleanupScheduler.java:25,35` | `0 0 4 * * *`, `0 5 3 * * *` | scheduler.enabled | `tryRun` | 예 |
| `API/finance/adapter/in/schedule/FinanceRegistrationReminderScheduler.java:25` | `0 0 21 L * *` | scheduler.enabled | `tryRun` | 예 |
| `API/market/adapter/in/schedule/FearGreedScheduler.java:27` | `0 0 7,12 * * *` | scheduler.enabled | `tryRun` | 예 |
| `API/web/UserEventResubmitScheduler.java:37` | fixedDelay 10분 | scheduler.enabled | 없음 | 예 |
| `API/web/AdminSchedulerController.java:26` | HTTP 수동 트리거(root) | scheduler.enabled | — | 라우팅 필요 |
| `API/notify/adapter/in/redis/PushNotificationStreamConsumer.java:54`, `API/admin/adapter/in/redis/AppErrorStreamConsumer.java:55` | fixedDelay 5분 복구 | **없음** | 없음 | 삭제 대상 |

주의: 이 중 `matchIfMissing=true`이므로 **web role에서 `scheduler.enabled=false`를 빼먹으면 전부 켜진다**. `AdminTradingSchedulerController`(`API/admin/adapter/in/web/AdminTradingSchedulerController.java`)는 root에서 trading-core 내부 API로 수동 트리거를 위임한다 — 합친 뒤 web이 받은 관리자 요청은 worker 빈에 닿아야 한다(같은 JVM이 아니다).

## G. Redis Pub/Sub·Stream

| 이름 | 종류 | 발행자 | 구독자 | 컨슈머 그룹 | 합친 뒤 |
|---|---|---|---|---|---|
| `trade.event` (`RedisPubSubConfig.java:15`) | Pub/Sub | trading `RedisTradeEventPublisher.java:25`(체결 리포트·코인 체결, 배치=worker) | root `RedisTradeEventSubscriber.java:30`(SSE, 모든 web), `TradeEventBenchmarkCacheInvalidator.java:29`(벤치마크 캐시 무효화) | 없음(전체 팬아웃) | **유지 필수.** worker(발행) → web N개(SSE emitter 보유)는 프로세스 경계이므로 Redis 필요. 이미 "여러 인스턴스 캐시 무효화"의 선례 |
| `stream:user.deleted` | Stream | root `UserEventStreamPublisher.java:29` | trading `UserDeletedStreamConsumer.java:17`→`UserEventRepublisher` | `TRADING_CONSUMER_GROUP`, 이름 `trading-core-{UUID}` | 프로세스 안 호출로 대체(삭제). 그 전까지 C-1 루프 위험 |
| `stream:user.notify-profile.changed` | Stream | root 같은 클래스 `:35` | trading `UserNotifyProfileStreamConsumer.java:18` | 같음 | 복제본 폐기와 함께 삭제 |
| `stream:app.error` (`RedisStreamConfig.java:11`) | Stream | trading `AppErrorStreamPublisher.java:26` | root `AppErrorStreamConsumer.java:33` | root 그룹 | 프로세스 안 `AppErrorRaisedEvent`로 대체(이중 기록 주의) |
| `stream:user.push-notification.requested` (`:12`) | Stream | trading `RedisPushNotificationPublisher.java:21-29` | root `PushNotificationStreamConsumer.java:32`(FCM) | root 그룹 | 프로세스 안 대체 가능. FCM 발송을 web·worker 어디서 해도 됨 |
| KIS 관리자 토큰 `kis:admin:token:{sha8}` (`KisAdminTokenCoordinator.java:27`) | Key | trading | trading | — | 유지(공유 캐시) |
| Toss 토큰 canonical·lease (`TossRedisTokenStore.java:65`) | Key | trading | trading | — | 유지(이미 분산 락) |
| JWT 블랙리스트 `RedisBlacklistAdapter` ×2 (`API/user/adapter/out/redis`, `TC/trading/adapter/out/security`) | Key | root | root·trading(`RedisTokenBlacklistReader` 공용 `SH/platform/security`) | — | 유지. 어댑터가 둘이니 합칠 때 한 벌로 |

web ↔ worker 사이에 여전히 필요한 것: `trade.event`(worker 배치 체결 → web SSE), 브로커 토큰·게이트 공유 상태(A1~A3 해결 시 Redis 추가), 통계 캐시 무효화(A12 해결 시 Pub/Sub 추가). 나머지 스트림 4종은 같은 JVM 호출로 대체 가능.

## H. 이미 다중 인스턴스를 견디는 근거 vs trading-core에 없는 것

현재 root는 **같은 이미지로 kista-api(HTTP, blue/green으로 교체 중 2개 겹침)와 kista-scheduler(단일)** 를 띄운다(`docs/agents/docker-infra.md:13,75`). 합친 구조의 선례가 이미 root에 있다.

| 장치 | root에 있음 | trading-core |
|---|---|---|
| role 플래그 | `scheduler.enabled` + prod yml(`application-prod.yml:20`) | 같은 플래그(`TradingApplication`)지만 role은 1개(kista-trading)라 "web만 띄우는" 구성이 없다 |
| 스케줄러 분산 락 | 6개 스케줄러 모두 `SchedulerLockService.tryRun` | 개장·마감·코인DCA·캘린더·키만료 `tryRun` 있음 |
| 재발행 소유자 단일화 | prod에서 API false·scheduler true로 명시, 주석에 이유 | env로 kista-trading에만 true, bats가 잠금 |
| 다중 인스턴스 캐시 무효화 | Redis Pub/Sub `trade.event` → 모든 인스턴스가 `BenchmarkService` 캐시 무효화(`TradeEventBenchmarkCacheInvalidator.java:20-45`), 놓치면 10분 TTL(`BenchmarkService.java:27`) | **없음.** `TradingStatsService`는 동기 `@EventListener` 프로세스 로컬 |
| SSE 팬아웃 | Pub/Sub로 모든 인스턴스에 전달(`RedisTradeEventSubscriber`). 상태 SSE는 미해결(A-3) | — |
| 토큰 저장·정지 | JWT 블랙리스트 Redis, refresh token DB | Toss는 Redis lease+fencing, **KIS·메리츠는 JVM 락 + DB 캐시** |
| 멱등·행 락 | — | 주문 상태 전이 행 락(B9)·사이클 행 락(B8)·Toss clientOrderId 멱등 있음 |
| trading-core에 **없는** 것 | — | (1) 계좌 단위 예산 락의 DB화(B1), (2) 앱키 호출 간격의 공유(A1), (3) KIS·메리츠 토큰 발급 직렬화의 공유(B2), (4) 통계 캐시 무효화의 프로세스 간 전달(A12), (5) 배치·재개 빈을 role로 한정하는 조건 |

## I. 요약

### I-1. 합치기 전에 반드시 해결 (미해결 상태로 web과 worker가 같은 trading 코드를 돌리면 돈·주문이 틀어진다)

| # | 항목 | 근거 | 해결 방향 후보 |
|---|---|---|---|
| M1 | **계좌 예산 락이 JVM 락뿐** (A9·B1) | `AccountBudgetLock.java:13` 주석이 직접 "다중 인스턴스가 되면 DB 락으로 승격". orders 유일 제약 없음. 수동 실행(web)과 배치(worker) 동시 시 PLANNED 이중 저장·원장 이중 사용 | (a) PG advisory lock(세션 단위 전용 커넥션, 임계 구역 본문이 DB 전용이라 가능) (b) Redis 락(Toss lease 패턴 재사용) (c) 수동 실행을 worker로 라우팅(blue/green 이점 상실). 보조: `orders`에 (사이클, 거래일, 슬롯) 유일 인덱스 추가 검토 |
| M2 | **앱키 호출 간격 게이트가 프로세스별** (A1·B4) | KIS 350ms·메리츠 110ms가 JVM 단위. web 조회와 worker 배치(계좌당 병렬 2)가 합산되면 한도 초과. 주문 POST는 재시도 없이 거절(`KisHttpClient.java:112-123`) → 배치 접수 실패 | (a) Redis 슬롯 예약(Lua `max(next,now)+interval`, 대기·거절 계약 유지, 키=앱키, 시계는 Redis TIME) (b) "KIS·메리츠 라이브 호출은 worker만, web은 캐시된 값만" 정책(화면 지연 감수) (c) 둘 다: 배치는 큰 슬롯, 사용자 호출은 짧은 대기 상한 |
| M3 | **KIS·메리츠 토큰 발급 직렬화가 JVM 락** (A2·B2) | EGW00133(1분 1회). 만료 순간 두 프로세스 동시 miss → 한쪽 실패(송신 전 확정 거절이라 주문 미접수) | `broker_tokens` 행에 `SELECT FOR UPDATE`/advisory lock 또는 Toss식 Redis lease를 `DbTokenCoordinator`에 일반화 |
| M4 | **같은 이벤트를 root·trading이 모두 듣는 문제 — 단계 1에서 즉시 발생** (C-1) | `UserEventStreamPublisher`(root) ↔ `UserEventRepublisher`(trading) 무한 순환 위험, `AppErrorRaisedListener`(root)+`AppErrorStreamPublisher`(trading) 이중 기록, `TradingUserCascadeListener`·`UserNotifyProfileSyncListener` 이중 실행 | 한 컨텍스트에 올리는 첫 커밋에서 role/프로파일 조건으로 스트림 발행자·컨슈머·재발행기를 끄거나, 스트림 경로를 먼저 제거(지침서 05 단계 2.2를 단계 1 앞으로) |
| M5 | **배치·재개·수동 트리거 빈을 worker 전용으로 한정** (A10·A11·B7·E·F) | `takeOver`가 "단일 인스턴스·비겹침" 전제(`SchedulerLockService.java:30-32`). `scheduler.enabled`는 `matchIfMissing=true`라 web에서 빼먹으면 전부 켜짐. `TradingSchedulerInternalController`·`AdminSchedulerController`는 조건부 빈이라 web에 없음 | `kista.role.worker` 조건을 스케줄러·`TradingBatchResumer`·`MarketCalendarRefreshScheduler`·`TradingBatch*`에 적용하고 기본값을 **꺼짐**으로 뒤집기. 관리자 수동 트리거는 DB 플래그/Redis 신호로 worker가 가져가거나 worker HTTP로 라우팅 |
| M6 | **EPR 저장소·재발행 소유 통일** (C 서두) | `public.event_publication`·`trading.event_publication` 두 벌, Modulith는 schema 하나. 모듈 개명 시 고아 row | 한쪽으로 통일 + 이전 전 미완료 row 소진, 재발행은 worker만 true, `pre-apply.sh`·`epr-orphans.sh` 런북 필수 |

### I-2. 합치면서 해결 (단계 1~3 중 모듈 이동과 함께)

| # | 항목 | 근거 | 해결 방향 후보 |
|---|---|---|---|
| S1 | 통계 캐시 무효화가 프로세스 간 전달 안 됨 (A12) | worker 배치 체결·web 투자금 변경이 다른 프로세스 캐시를 못 비움(TTL 5분) | `trade.event`처럼 Redis Pub/Sub 무효화(선례 `TradeEventBenchmarkCacheInvalidator`), 또는 TTL을 30~60초로 낮춰 수용 |
| S2 | KIS 연결 테스트 `tempTokenCache`가 JVM-local (A4) | 다음 요청이 다른 web이면 재발급 → EGW00133 | Redis에 TTL 90초로 보관, 또는 연결 테스트·계좌 검증을 한 요청으로 합침 |
| S3 | `verifyAccount`가 게이트 우회 (D) | `KisAuthApi.java:114-` 직접 `kisRestClient` | M2 해결 시 같은 게이트를 태움 |
| S4 | 상태 SSE(`SseEmitterRegistry`)가 이벤트 발행 인스턴스에만 전달 (A-3) | 승인·반려 푸시 유실 | Pub/Sub 팬아웃(`trade.event` 방식) 또는 클라이언트 폴링으로 수용 |
| S5 | search_path·스키마 해석 (B7·C) | 두 서비스가 서로 다른 `connection-init-sql`(`finance,kista_ref,public` ↔ `trading,trading_ref`)과 비한정 SQL(`SchedulerLockService` `scheduler_locks`, Modulith EPR) | 합칠 때 한 DataSource의 search_path를 정하고 비한정 SQL·락 테이블 한 벌로. trading 스키마 마이그레이션에도 expand/contract 적용 |
| S6 | Redis 스트림 4종 삭제와 컨슈머 복구 `@Scheduled`(게이트 없음) (G·F) | 합친 뒤 불필요 | 삭제. 삭제 전엔 worker 전용 |
| S7 | `UserEventResubmitScheduler` 2벌·알림 리스너 중복(`alerted` 등) (A15) | 이중 구현 | 한 벌로 합치고 `alerted`는 Redis `SETNX` 또는 DB 컬럼으로 |
| S8 | 알림 리스너가 worker·web 양쪽에서 필요한 시크릿(텔레그램·FCM·Redis)을 가짐 (C-1) | EPR 재발행은 worker가 하므로 web 발행분도 worker에서 실행 | 두 role 동일 env, 비밀값 compose 정의 점검 |
| S9 | 블랙리스트 어댑터 2벌(`RedisBlacklistAdapter`) | G | 한 벌 |

### I-3. 무해 (프로세스별로 따로 있어도 정합성에 영향 없음)

| 항목 | 이유 |
|---|---|
| `UsdKrwRateCache`, `TossPriceApi.prevCloseCache`, `TossCandleApi.latestDailyCache`, `CryptoMarketIndicatorService` 캐시, `DcaPlanInputCollector` 캐시(A6~A8·A13·A14) | 순수 조회 캐시, 값이 프로세스 간에 같거나 TTL 안에서 수렴. 호출 수만 증가 |
| `RuntimeSettings`·정책 설정(A-3) | 캐시 없이 DB 직접 조회 |
| 토스 토큰(A5) | 이미 Redis 분산 |
| 주문 상태 전이·사이클 원장 행 락(B8·B9), Toss clientOrderId 멱등 | DB 기반이라 프로세스 무관 |
| `CryptoDcaRunner.lastReported`, `TradingBatchRunState`, `TradingBatchSerialExecutor`, `TradingParallelRunner` | worker 전용이면 단일 프로세스 가정 유지 |
| Upbit·Binance 어댑터 | 간격 제어가 원래 없고 한도가 IP 단위(같은 서버) |

### I-4. 판정 참고 (Opus용)

- **막는 항목은 M1(예산 락)·M2/M3(브로커 한도·토큰)·M4(단계 1 순환)·M5(role 가드)·M6(EPR)**. 이 중 M4·M5·M6은 코드 구성 문제라 단계 1 설계에 넣으면 되고, **M1·M2·M3은 "web에서 매매·브로커 호출을 받겠다"는 결정의 직접 비용**이다.
- 비용을 줄이는 분기: web이 브로커를 부르지 않게 하면 M2·M3·S2·S3가 사라진다. 그러나 수동 매매(M1)·잔고·시세 조회·전략 등록 시 전일종가 조회가 전부 브로커 호출이라 사실상 불가능하다. 따라서 M2·M3은 Redis 공유로 푸는 쪽이 현실적이다(Toss 토큰의 Redis lease가 이미 구현 선례).
- M1은 임계 구역 본문이 DB 전용이라 DB 기반 락으로 바꾸는 비용이 작다는 것이 확인됐다(브로커 포트 미사용). 다만 본문이 여러 짧은 트랜잭션이라 "트랜잭션 하나로 감싸기"는 의미 변경이 있어 세션 단위 advisory lock이나 Redis 락이 안전하다.
- 이번 조사에서 확인하지 못한 것: 전략 정지·삭제와 배치의 목록 스냅샷 경합(B11, 현행과 동일 구조라 보류), `orders`에 슬롯 유일 인덱스가 없다는 사실의 의도 여부(V1 외 마이그레이션 grep 기준), blue/green 겹침 시 현행 kista-api가 이미 가진 SSE 상태 유실 빈도.
