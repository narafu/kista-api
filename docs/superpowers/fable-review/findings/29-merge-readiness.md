# 29. 통합(05 단계 M) 착수 전 재측정 — 위험·충돌·M 목록

기준: 2026-11-15 main `252e63bfe`(운영 3 role 전부 이 SHA). 코드는 고치지 않았다. 1~3절은 Fable의 판정이고, 부록 A~D는 Sonnet 측정 원본(file:line)이다. 이 문서에서 나온 결정은 `guides/05-modules-and-processes.md` 14절에 있다.

운영 읽기 전용 조회(2026-11-15 11시경 KST):

| 항목 | 값 |
|---|---|
| 호스트 | 2 CPU, 11.9GB(사용 2.5GB). 컨테이너 실사용 api 513MB / scheduler 471MB / trading 525MB |
| `event_publication` 미완료 | `trading` 0건(전체 365), `public` 0건(전체 865). 두 테이블 컬럼 9개 동일 |
| Redis 스트림 4종 | 전부 pending 0, lag 0 |
| `max_connections` | 100. JDBC 연결 7개 |
| live 계좌·전략 | 계좌 5(메리츠 3, 토스 1, MOCK 1), 전략 6(한 계좌에 4개), 활성 사용자 2 |
| `scheduler_locks` | `trading` 7행(live), `public` 16행 — 그중 `trading-open`·`trading-close`·`market-calendar-*`·`fear-greed-*` 7행은 2026-06~09의 낡은 행(스키마 분리 전 잔재) |

## 1. 위험 재측정 (`findings/09`)

### 1-1. 판정 요약

A1~A19·B1~B11 30행 중 그대로 24, 달라짐 5(A9·B1·B9·B11은 S11, A18은 Alpaca 캐시 추가), 해소 0. I-2의 S1~S9 중 S9만 해소(`f5e707675`). 행별 표는 부록 A의 1절.

| `findings/09` I-1 | 판정 | 지금 상태 |
|---|---|---|
| M1 계좌 예산 JVM 락 | **달라짐**(`0cd178893`, S11) | 아래 1-2 |
| M2 호출 간격 게이트 | 그대로 | 메리츠 110ms·대기 상한 20초, 키는 appKey, JVM 단위. 토스는 게이트 없음. 세 벤더 모두 주문 POST는 한도 재시도가 없다 |
| M3 토큰 발급 JVM 락 | **달라짐**(범위 축소) | 벤더 쪽 "1분 1회" 제한은 KIS에만 있다(`vendors/kis.md:22`). 메리츠는 만료 전 재발급이 같은 토큰을 돌려주는 멱등(`vendors/meritz.md:14`), 토스는 이미 Redis lease. **04 단계 A(KIS 삭제)가 끝나면 M3은 사라진다** |
| M4 같은 이벤트를 R·T가 모두 듣는다 | 그대로 | 리스너·발행 지점·스트림 클래스 전부 그대로(부록 A 5절). 발행 지점은 모두 호출자 트랜잭션 안. `AppErrorRaisedEvent` 발행 주체만 `ApiExceptionReporter`·`TradingExceptionReporter`로 바뀌었다(S5) |
| M5 worker 전용 빈 한정 | 그대로 | `scheduler.enabled` 게이트 16곳(R 8, T 8), `matchIfMissing=true`. 게이트 없는 `@Scheduled` 3개(스트림 복구) |
| M6 EPR 저장소 2벌 | 그대로 | 운영 미완료 0건 |

새로 생긴 프로세스 로컬 상태는 `AlpacaMarketDataAdapter`의 `TtlResultCache` 하나다(S15). 지금은 배치만 부른다. 14 T3 뒤에도 시세를 읽는 경로(미리보기·전략 등록)는 엔진 경로라 worker에만 있다 — 1-1 원칙 아래에서는 무해하다.

### 1-2. M1을 S11이 얼마나 덜었나

`findings/09`는 "수동 매매(web)와 배치(worker)가 겹치면 PLANNED 이중 저장 + 예산 이중 차감"이라고 했다. 지금 코드로는 이렇다.

1. **예산은 계좌가 아니라 사이클 단위다.** `StrategyLedgerBudget.remaining`(`:38-53`)은 그 사이클의 `cycle_position.deposit`에서 그 사이클·그 날의 BUY 합계를 뺀다. 계좌 예수금도 다른 전략도 읽지 않는다. 그래서 "같은 계좌의 다른 전략끼리 한 예산을 나눠 쓴다"는 경로는 없다. `AccountBudgetLock`의 범위(계좌)는 불변식의 범위(사이클)보다 넓다.
2. **같은 사이클·같은 슬롯의 이중 저장은 DB가 막는다.** `uq_orders_live_slot (strategy_cycle_id, trade_date, timing, direction, order_leg)`. 수동과 배치는 같은 커널·같은 `Order.fromPlanned`를 지나 같은 키를 만든다(부록 A 2-e). 둘째 저장은 통째로 롤백된다(한 `saveAll` = 한 트랜잭션).
3. **남는 구멍은 둘이다.**
   - 재캡의 낡은 예산: `TradingOrderExecutor:122-125`가 `remaining()`을 락 밖에서 구한 뒤 `capIfNeeded`가 사이클 행 락을 잡는다. 두 프로세스가 같은 사이클을 동시에 재캡하면(worker 개장 접수 + web 수동 접수) 늦은 쪽이 앞선 쪽의 증가분을 못 본다. 재캡이 BUY 총액을 늘릴 때만이고 새 leg라 슬롯 충돌도 없다. 창은 좁다.
   - 인출·추가 대 저장: 지금 단일 JVM에서도 락을 공유하지 않는다. 합쳐도 나빠지지 않는다.
4. **막히긴 하는데 곱게 막히지 않는다.** 위반 시 수동 실행은 매핑이 없어 500이고(409가 아니다), 배치는 그 전략 하나가 저장 실패 + 오류 알림으로 끝난다. `LedgerUniqueConstraintIT`는 DB와 flush 순서만 단언하고 앱의 반응은 보지 않는다.

정리하면 S11은 M1을 "돈이 틀어진다"에서 "한쪽이 실패한다 + 좁은 재캡 경합"으로 낮췄다. 없앤 것은 아니다.

### 1-3. 1-1 원칙("매매 엔진은 한 프로세스에서만")이 여전히 필요한 조건

| 조건 | 근거 | 04 A·15 뒤 |
|---|---|---|
| 메리츠 호출 간격이 JVM 단위 | `MeritzHttpClient.java:44` `new CallIntervalGate(110, 20_000)`, 주문 POST는 한도 초과 시 재시도 없이 거절(`:133`). live 메리츠 계좌 3개, 한 계좌에 전략 4개 — 배치가 계좌당 동시 2개(`TradingParallelRunner`)로 쏘는 중에 web 조회가 같은 appKey를 치면 합산 간격이 깨진다 | 그대로 |
| 재캡 예산 경합 + 위반 시 500 | 1-2의 3·4 | 그대로 |
| 배치 재개의 락 인수 | `SchedulerLockService.takeOver`가 "단일 인스턴스·비겹침" 전제(`:29-32`), 마감 예약이 메모리(`TradingCloseScheduler:53`) | 그대로 |
| KIS 토큰 1분 1회 | `KisAuthApi` EGW00133 | **사라짐** |

원칙을 풀려면(05의 8절 단계 C) 이제 필요한 것은 넷에서 셋으로 줄었다: 메리츠 게이트의 공유, `remaining()`을 사이클 락 안으로 옮기기, 유일 제약 위반을 409로 매핑. 토큰 lease 일반화는 KIS 삭제로 빠진다. 그래도 지금 규모(계좌 5, 전략 6)에서 풀 이유는 없다. **원칙 유지.**

## 2. 충돌 재측정 (`findings/10`)

판정이 바뀐 것만 적는다. 나머지는 그대로다(부록 B).

| 항목 | 판정 |
|---|---|
| G-1 `@RestControllerAdvice` 2개 | 해소(`0d23b822b`) |
| A4 `RedisBlacklistAdapter` 2벌, `TokenBlacklistPort` 후보 2개 | 해소(`f5e707675`) |
| B2 `fearGreedRestClient` 빈 이름 | 해소(`f5e707675`) |
| E-3 `SchedulerLifecycleEvent` 알림 | 해소(`1260139b8`) |
| G-3 `PlainDecimalSerializer` 2벌 | 대상 없음(클래스가 없다) |
| G-3 `MarketSessionResponse` 스키마명 | 해소(contract 쪽이 `@Schema(name="InternalMarketSessionResponse")`) |
| **`ExceptionReporter` 빈 후보 2개** | **새 충돌.** S5가 만들었다. `ApiExceptionReporter`·`TradingExceptionReporter`가 모두 `@Component`이고 `ProblemDetailExceptionHandler.java:104`가 `ObjectProvider.getIfAvailable`로 받는다. 합치면 `NoUniqueBeanDefinitionException` |
| A3·B1 `ClockConfig`, `clock` 빈 | 그대로(본문 동일) |
| A5 `UserEventResubmitScheduler` 빈 이름 | 그대로. **두 벌이 같지 않다** — T는 `AccountDeletedEvent`까지 거른다(상위집합) |
| B-1 값이 다른 yml 키 | 그대로 11개(전부 표에 있다). 표에 없는 한쪽 키 6개: R base `logging.level.root`·`logging.level.com.kista`, R local 4개(`show-sql` 등) |
| C `nativeQuery` | R 16 + T 39 = 55개, 전부 스키마 한정 없음. `JdbcTemplate` SQL 4파일 |
| G-2 같은 메서드+경로 | 0건(컨트롤러 55개, 핸들러 R 106 / T 71) |
| 락 이름 | R 8 / T 7, 겹침 0. 엔티티 단순명 중복 0. `@Entity` 42개 전부 `@Table(schema=)` 명시(2026-11-15 추가 확인 — 부록 B가 못 본 여러 줄 선언 5개 포함. `BaseAuditEntity`·`BaseCreatedAtEntity`는 `@EntityListeners`라 엔티티가 아니다) |
| K-1 `deploy/server/readiness`, `schema-reorg` | 없어졌다 |

## 3. M 커밋 목록 재검증

### 3-1. 지침서 05 4-1절과 달라진 것

| # | 지침서 | 지금 |
|---|---|---|
| M1 | jar 2개 | 이미지는 원래 1개다(`APP_JAR`로 jar만 고른다). `shared/build.gradle.kts:80`에도 `bootJar` 설정이 있다 |
| M2 | `@EntityScan` 등에 두 패키지 명시 | 지금은 어디에도 없다(기본 스캔). 새로 넣는다. `@ConfigurationPropertiesScan`은 T에만 있고 대상은 `HeartbeatProperties`·`CoinMarketCapProperties` 2개 |
| M5 | `SPRING_FLYWAY_BASELINE_ON_MIGRATE` 대체 | compose·yml·Java 어디에도 이 키가 없다. 대체할 것이 없다 |
| M9 | 삭제 목록 | `PlainDecimalSerializer` 없음. **`ExceptionReporter` 한 벌 추가.** `UserEventResubmitScheduler`는 T 쪽을 남긴다(상위집합, 이미 `adapter/in/schedule`) |
| M11 | 구 scheduler 잡이 내부 API를 부르면 localhost | `@Scheduled` 잡 → 내부 HTTP 사슬은 0건. 다만 `/api/admin/scheduler/**`(worker로 라우팅)의 `AdminTradingSchedulerController`가 내부 HTTP로 수동 트리거를 부른다. worker의 `INTERNAL_API_BASE_URL`은 여전히 `http://localhost:8080`이 필요하다 |
| M11 | 내부 핸들러 31개 | 30개(컨트롤러 13개). R 어댑터 11개·메서드 28개 |
| M12 | "R → T 금지" 규칙 삭제 | 그런 규칙이 없다. 대신 `ApplicationContextLoadTest:60-66`이 "trading-core 빈이 없다"를 단언해 합치면 실패한다. JPA 저장소 수 단언 21 + 20 |
| M12 | `TradingApplication` 참조 테스트 6개 | 코드 참조 4파일 + 주석 1 + `TradingApplicationContextLoadTest` |
| M12 | `RootDataJpaTestBase` 상속 20개 | 상속 19개 |
| M13 | 스키마명 충돌 3건 | `MarketSessionResponse`는 해소. **`Market`·`InvestmentPoint`도 충돌하지 않는 것으로 본다(2026-11-15 추가 확인, 정적 근거)**: enum을 컴포넌트로 올리는 설정(`enumAsRef` 등)이 0건이라 enum은 필드에 인라인된다 — finance `Market`은 요청 DTO 2개(`AssetSnapshotRequest`·`BulkFinanceRegisterRequest`)의 필드일 뿐이고 `shared.kernel.Market`을 import하는 `*Request`/`*Response`는 없다. `InvestmentPoint`는 R·T 모두 도메인 타입이고 T 쪽은 스펙에서 제외되는 `/api/internal/trading/stats`에서만, 그것도 contract `InvestmentPointsResponse`로 바뀌어 나간다. 합친 스펙을 받아 확정하는 일은 M13에 남는다. `OpenApiSchemaNameCollisionTest`가 R·T에 한 벌씩이라 서로의 충돌은 못 잡는다 |
| M14 | `spring.task.scheduling.pool.size` 명시 | 두 앱 모두 `spring.threads.virtual.enabled: true`다. Boot는 이때 풀이 아닌 가상 스레드 스케줄러를 쓴다 — 풀 크기를 정할 대상이 없을 가능성이 높다(14절 기본값 참고) |
| M14 | Hikari "JVM당 5" | prod 5 맞다. base는 10 |
| 13절 | `HexagonalArchitectureTest:179-180` | `:210-211`로 밀렸다. 줄 번호 대신 규칙 이름으로 찾는다 |
| 13절 | P4 가드 2개 | `SchedulerRoleConfigTest`가 빠져 있었다 |

M10의 신설 대상(`RoleRoutingGuard`·`RoleRoutes`·`kista.role`·`ConditionalOnWorkerRole`·`WRONG_ROLE`·`ENGINE_RESTARTING`)은 전부 0건이다. `single-impl-interfaces.txt` 82줄 = `PENDING_REMOVAL_CEILING` 82라 여유가 0이다 — 단계 M·X·R에서 구현 하나짜리 인터페이스를 새로 만들면 가드에 걸린다.

### 3-2. 남은 선행 작업이 M 항목에 주는 영향

KIS 전용: main 15파일 1,766줄 + test 10파일 1,904줄, yml 키 3, 빈 12, 가드 5곳. 코인 전용: main 68파일 3,967줄 + test 35파일 5,257줄, yml 키 3, 스테레오타입 빈 34 + RestClient 7, 컨트롤러 2(엔드포인트 3), 스케줄러 1(`crypto-dca`).

| M 항목 | W2(P3·P4) | P6 | 04 A(KIS) | 15(코인) |
|---|---|---|---|---|
| M1 빌드·Dockerfile | — | — | — | — |
| M2 앱 클래스 | — | — | — | 작아짐(`CoinMarketCapProperties` 소멸 → T properties 1개) |
| M3 yml 병합 | 작아짐(`scheduler.enabled` → `kista.role`이 미리 들어가 있다) | — | 작아짐(T 전용 키 3개 소멸, 충돌 키는 아니다) | 작아짐(키 3개) |
| M4 search_path | **선행 필수.** P3 없이는 M4가 성립하지 않는다 | — | — | — |
| M5 Flyway | — | — | — | — |
| M6 락 테이블 | 작아짐(P3이 테이블 이름을 프로퍼티로) | — | — | 작아짐(`crypto-dca` 락 소멸) |
| M7 EPR, M8 모듈 ID | — | — | — | — |
| M9 삭제 | — | 목록만 확정(이 문서로 끝났다) | — | — |
| M10 role 가드·Caddy | **선행 필수.** P4가 `kista.role`과 `@ConditionalOnWorkerRole`을 만든다 | — | — | 작아짐(`crypto-market-indicators` 경로, `TradingSecurityPolicy` 소멸) |
| M11 내부 HTTP | — | — | — | — |
| M12 테스트 | 작아짐(P4가 `SchedulerDisabledContextTest`·`SchedulerRoleConfigTest`를 먼저 고친다) | — | 저장소 수 단언 −1(`KisTokenJpaRepository`) | — |
| M13 springdoc | — | — | — | 작아짐(코인 DTO) |
| M14 리소스 | — | — | 작아짐(빈 12) | 작아짐(빈 41, 10분 틱 소멸) |
| 1-1 원칙의 전제 | — | — | **M3 위험 소멸** | — |
| 전환 당일 | — | — | — | 10분마다 도는 `crypto-dca` 틱이 없어져 전환 창이 조용해진다 |

읽는 법:

- **사라지는 M 항목은 없다.** 단계 M은 구조 작업이라 KIS·코인 삭제로 크기가 거의 줄지 않는다. 줄어드는 것은 단계 X(X6의 호출처 2개)·R(kernel의 `Dca*`·`Broker` 상수)과 운영 표면(빈 53개, 스케줄러 1개)이다.
- **P3은 KIS·코인과 겹치지 않는다.** KIS·코인 전용 파일에는 native SQL도 `JdbcTemplate` SQL도 0개다. 로드맵 W2의 "W1 뒤에 하는 편이 낫다(삭제될 KIS 쿼리를 고치지 않는다)"는 근거가 없다. 지금 해도 버려지는 작업이 없다.
- W2는 M의 선행이고, 04 A와 15는 선행이 아니다. 순서 결정은 05의 14-1절.

---

# 부록 A. `findings/09` 재판정 원본


### 2. M1 심층

### 2-a. AccountBudgetLock 현재 본문과 사용처
- 본문 `TC/trading/application/service/AccountBudgetLock.java:15-34`: package-private `@Component`, `ConcurrentHashMap<UUID,ReentrantLock> locks`(:17), `call(UUID accountId, Body)` = `computeIfAbsent`→`lockInterruptibly`→body→`unlock`(:25-33). 주석(:10-13) "예약 합계 → live 조회 → PLANNED 커밋을 같은 계좌끼리 겹치지 않게", `// ponytail: JVM 내 락 — kista-trading 단일 인스턴스 전제, 다중 인스턴스가 되면 DB 락(계좌 row FOR UPDATE)으로 승격`(:13). 판정: **그대로**(`git log -- AccountBudgetLock.java` = 1f92c19ad·2814af658, 이후 변경 없음). 클래스가 09 때와 달리 package-private(:15).

| 사용처 file:line | 임계 구역이 하는 일 | 읽기 | 쓰기 | 브로커 포트 호출 |
|---|---|---|---|---|
| `TC/trading/application/service/TradingCandidatePlanner.java:258` (`allocateAndSave` 본문 :296-330, 계좌별 `TradingParallelRunner.Task` 안) | 배치 개장·마감 후보의 원장 판정+PLANNED 저장 | `orderPort.findPlannedOrPlacedByCycleAndDate`(:307), `ledgerBudget.remaining`(:315-316 → `StrategyLedgerBudget.java:38-42`: `cyclePositionPort.findLatestOne`·같은 날 주문·`batchRunPort.isReported`·`findCancelledByCycleAndDate`) | `orderPlanner.savePlannedOrders`(:318 → `orderPort.saveAll`), DCA면 `recordDcaOrdered`(:325, DCA 이력 기록) | 없음 (전부 DB 포트) |
| `TC/trading/application/service/ManualTradingService.java:109-125` | 수동 실행 승인+저장 | `findPlannedOrPlacedByCycleAndDate`(:111, 락 안 재검사), `ledgerBudget.remaining`(:116) | `orderPlanner.savePlannedOrders`(:124) | 없음. 단 락 **밖**에서 `planBuilder.build`(:79)·`fetchStartPriceOrNull`(:99)가 브로커 호출 |
| `TC/trading/application/service/TradingOrderExecutor.java:122-125` (`capWithBudget` :110, 호출 `applyCap` :99, 개장 :60·마감 :88) | 접수 직전 BUY 재캡(총액이 늘 때만) | `ledgerBudget.remaining(cycle,date)`(:124) + capper 내부 `loadBuyOrders` | `buyOrderPriceCapper.capIfNeeded`(@Transactional, `BuyOrderPriceCapper.java:67-95`: `cancelPlanned`+`saveAll`) | 없음. 접수(`placeEach` :148~)는 락 밖 |
- 테스트 사용: `TradingServiceTest:106,185`, `ManualTradingServiceTest:104,310-333`, `TradingCandidatePlanner{Ledger,Dca}Test`, `TradingOrderExecutorTest:118`.

### 2-b. trading V21 전문 (`trading-core/src/main/resources/db/migration-trading/V21__ledger_unique_constraints.sql`)
```sql
-- 원장을 지키는 유일 제약 2개 — 재개 결함·수동 실행과 배치의 겹침으로 같은 일이 두 번 돌아도 이중 주문·이중 사이클 대신 저장 실패가 나게 한다
-- 적용 전 확인: 아래 두 조건의 GROUP BY … HAVING count(*) > 1 이 운영에서 0건이어야 한다(2026-11-15 0건)
CREATE UNIQUE INDEX uq_orders_live_slot
    ON trading.orders (strategy_cycle_id, trade_date, timing, direction, order_leg)
    WHERE status IN ('PLANNED', 'SUBMITTING', 'PLACED', 'PARTIALLY_FILLED', 'FILLED')
      AND order_leg <> 'UNKNOWN';
CREATE UNIQUE INDEX uq_strategy_cycle_open
    ON trading.strategy_cycle (strategy_id)
    WHERE end_date IS NULL AND deleted_at IS NULL;
```
(주문 인덱스 키에 timing·direction 포함 — 09 시점 "유일 제약 없음"은 **해소됨(0cd178893)**.)

### 2-c. 유일 제약 위반 시 반응
- `DataIntegrityViolationException`/`DuplicateKeyException`을 잡는 곳 (main 전수 grep): `account/.../AccountService.java:161`(계좌번호 hash), `trading/.../StrategyCreationService.java:134`(활성 ticker 인덱스). **orders·strategy_cycle 저장 경로에는 catch 없음.** 테스트에만 등장.
- 배치 `TradingCandidatePlanner.allocateAndSave`: 저장이 `batchGuard.runSafely("원장 판정·계획 주문 저장", ctx, ...)`(TC/trading/application/service/TradingCandidatePlanner.java:313-323) 안. `TradingBatchGuard.runSafely`(`TC/trading/application/service/support/TradingBatchGuard.java:27-37`)가 `Exception`을 잡아 `log.error` + 관리자·사용자 `TradingErrorEvent` 알림 후 `Optional.empty()` → **그 전략(사이클) 하나만 저장 실패·접수 대상 제외**, 같은 계좌의 다음 슬롯·다른 계좌는 계속(for 루프 :302 안, 예외는 runSafely에서 흡수). 배치 전체 실패 아님. 단 `saveAll`은 자체 트랜잭션이라 슬롯 일부만 들어간 채 실패할 수는 없고(한 saveAll=한 tx) 전부 롤백.
- 수동 `ManualTradingService.execute`: `@Transactional` 없음(grep 0건), 락 안 `orderPlanner.savePlannedOrders`(:124)의 예외는 `catch (InterruptedException)`(:126)만 있어 그대로 전파. `ProblemDetailExceptionHandler.handleAll`(`SH/platform/web/ProblemDetailExceptionHandler.java:80-95`)에서 `TradingProblemDetails`(`TC/trading/adapter/in/web/TradingProblemDetails.java:25-33`)에 DataIntegrity/DuplicateKey 매핑이 없어 미매핑 → `report(ex)` + `log.error` + **HTTP 500 "예기치 않은 오류가 발생했습니다."**(409 아님). (사전검사 :72, :111에 걸리면 `AlreadyOrderedTodayException`→409.)
- 재캡 `BuyOrderPriceCapper.capIfNeeded`: `TradingOrderExecutor.applyCap`(:99-107)이 `OrderStateConflictException | PessimisticLockingFailureException`만 잡는다. DataIntegrity는 안 잡혀 `placeAtOpenOrders`/마감 접수 호출 스택으로 전파(재캡 트랜잭션은 롤백, 남은 PLANNED는 그대로).
- 사이클: `StrategyService.reopenCycleIfEnded`(재개) 동시 2회 → 둘째가 `uq_strategy_cycle_open`에 막혀 미매핑 500 (guides/11-db-tables.md:266 ①이 같은 내용을 "13 U8에서 정함"으로 보류 기록).
- `LedgerUniqueConstraintIT`(`trading-core/src/test/.../trading/adapter/out/persistence/LedgerUniqueConstraintIT.java`, 커밋 0cd178893) 단언: 같은 슬롯 PLANNED 2회 저장 시 `em.flush()`가 `uq_orders_live_slot` 메시지로 실패(:72-78), 살아있는 상태(PLANNED/SUBMITTING/PLACED/PARTIALLY_FILLED/FILLED)끼리 공유 불가(:83-86), 취소·실패 행은 새 PLANNED 허용(:90), 죽은 행을 살리는 전이는 `DuplicateKeyException`(:101-106), 슬롯(timing/direction) 다르면 공존(:110), UNKNOWN leg 제약 밖(:124), PLANNED 삭제 후 재삽입·취소 후 같은 슬롯 재삽입이 한 트랜잭션에서 통과(:131,:142), 열린 사이클 2번째 저장 실패(`uq_strategy_cycle_open`, :158-161), 종료 후 새 사이클 회전 통과(:165), 종료·삭제 사이클 제약 밖(:176). **애플리케이션이 위반을 어떻게 처리하는지는 단언하지 않는다**(DB·flush 순서만).
- flush 순서 수정 2곳 실재: `OrderPersistenceAdapter.java:107-111`(`cancelPlanned`→`saveAndFlush`), `StrategyCyclePersistenceAdapter.java:59-62`(`markEnded`→`jpaRepository.flush()`). 27번 문서의 "위반 가능(높음)" 두 건은 **해소됨(0cd178893)**.

### 2-d. 예산 계산 범위 — 사이클(전략) 단위 독립
- 원장 잔여 `StrategyLedgerBudget.remaining(cycle, tradeDate)`(`TC/trading/application/service/StrategyLedgerBudget.java:38-53`) = 그 사이클의 `cycle_position.deposit`(없으면 `cycle.startAmount()`, :46) − **그 사이클·그 날** PLANNED/SUBMITTING/PLACED BUY 금액 합(:48-51, `orderPort.findPlannedOrPlacedByCycleAndDate(cycle.id(), …)` :40) − 리포트 전 취소 BUY 체결분(:56-70). 클래스 주석(:24) "전략 자기 원장 기준 … 계좌 단위 예산 배정(live 잔고 조회)을 대체". 계좌 예수금·다른 사이클·다른 전략은 읽지 않는다. 호출처 전수: `TradingCandidatePlanner:316`, `ManualTradingService:116`, `TradingOrderExecutor:124`, `CapitalLedgerService:142`(인출), `DcaPlanInputCollector:123`, `TradingPreviewService:291`.
- **결론: 같은 계좌의 서로 다른 사이클/전략은 한 예산을 공유하지 않는다.** 원장 상한은 사이클별 독립이다. 따라서 계좌 단위 `AccountBudgetLock`이 직렬화하는 범위(계좌)는 불변식 범위(사이클)보다 넓다. 같은 사이클·같은 슬롯 이중 저장은 `uq_orders_live_slot`이 DB에서 막는다.
- 재캡/락: `BuyOrderPriceCapper.capIfNeeded`는 `strategyCyclePort.lockForUpdate(cycleId)`(`BuyOrderPriceCapper.java:71`, 구현 `StrategyCyclePersistenceAdapter.java:34`, `PESSIMISTIC_WRITE`)로 **같은 사이클의 재캡끼리** 직렬화(cancel-read-write). 락 해제는 tx 종료. 이 행 락 사용처: `BuyOrderPriceCapper:71`, `CapitalLedgerService:199`(투자금 추가·인출), `StrategyUpdateWriter:106`, `ManualTradeCorrectionService:70`, `CryptoLedgerRecorder:38`. **배치 `allocateAndSave`·수동 `execute` 저장 경로는 사이클 행 락을 잡지 않는다**(JVM 계좌 락만).
- 유일 제약으로 막히지 않는 "다른 슬롯 동시 저장 → 예산 초과" 경로 (코드 근거):
  1. **인출/추가 vs 저장**(이미 현행 단일 JVM에도 존재): `CapitalLedgerService.withdraw`(:110-)는 사이클 행 락 아래서 `remaining`으로 가능액 판정(:142)하지만 배치·수동 저장은 그 락도 JVM 계좌 락도 공유하지 않는다. 새 BUY 저장은 INSERT이고 인출은 원장 UPDATE라 슬롯 충돌이 없어 유일 제약이 못 막는다. 합쳐도 악화되지 않는 사전 경합(`CapitalLedgerService.java:126`의 코인 `ponytail:` 주석과 같은 유형).
  2. **재캡의 stale 예산**: `TradingOrderExecutor.java:122-125`에서 `ledgerBudget.remaining()`을 인자로 구한 뒤 `capIfNeeded`가 별도 tx에서 사이클 락을 잡는다(:71). 두 프로세스(worker 개장 접수 + web 수동 `placeAtOpenOrders`)가 같은 사이클을 동시에 재캡하면 락은 직렬화하지만 `remaining`은 락 밖에서 구해 늦은 쪽이 앞선 쪽 커밋 증가분을 못 본다(재캡이 BUY 총액을 늘릴 때만, 새 leg 이름이 달라 슬롯 충돌 없음). 좁은 창이며 현재도 JVM 락이 이 구간을 직렬화하던 것이 사라진다.
  3. 같은 사이클의 같은 plan을 배치·수동이 동시 저장하는 경우(핵심 시나리오)는 둘 다 같은 (cycle,date,timing,direction,leg) 슬롯을 만들므로 **유일 제약이 막는다**(2-e). 두 번째가 위반으로 실패하므로 원장 이중 차감은 저장 자체가 거부돼 안 생긴다.
  4. 다른 사이클끼리는 예산을 공유하지 않으니 서로 영향 없음(계좌 락이 필요 없는 구간).

### 2-e. 수동 vs 배치 슬롯 키
- 둘 다 같은 `StrategyOrderPlanBuilder`·전략 커널로 계획 → `PlannedOrder`(leg·timing·direction·tradeDate)를 `Order.fromPlanned`(`TC/trading/domain/model/Order.java:64-68`)로 같은 필드 그대로 승격. 수동은 `planBuilder.build`(`ManualTradingService.java:79`), 배치는 `collectCycleCandidate`(`TradingCandidatePlanner.java:87,109,134`, 마감=`EnumSet.of(AT_CLOSE)`, 개장=AT_OPEN만 `TradingService.java` 개장 경로). → (strategy_cycle_id, timing, direction, order_leg)가 같은 슬롯 집합(수동은 AT_OPEN·AT_CLOSE 전부 생성, 배치는 개장/마감이 나눠 생성하므로 겹치는 슬롯은 같은 키).
- trade_date: 수동 `today = SessionTradeDates.nextTradeDate(now, calendar)`(`ManualTradingService.java:65`·`SessionTradeDates.java:15-18`, 실제 마감 배치 시각 이후면 다음 날), 배치 마감 `LocalDate.now(clock)`(`TradingService.java:86`). 마감 배치 시각 이전엔 같은 날짜, 이후엔 수동이 다음 거래일 날짜를 써 슬롯이 갈라져 충돌도 중복차단도 없음(설계 의도).
- 중복 차단 순서: 수동은 저장 전 `findPlannedOrPlacedByCycleAndDate` 비어있음 검사 2회(:72 락 밖, :111 락 안)→`AlreadyOrderedTodayException`(409), 배치는 `excludeExisting`으로 같은 슬롯 제외(:306-307). 두 프로세스 동시 진입이면 두 검사가 모두 빈 결과를 볼 수 있고 최종 방어선이 `uq_orders_live_slot`(→ 수동 500 / 배치는 해당 전략 1건 알림).

### 3. M2 — 호출 간격 게이트
`CallIntervalGate`(`TC/broker/adapter/out/internal/CallIntervalGate.java:12-47`): `ConcurrentHashMap<String,AtomicLong> nextSlotByKey`(:16), `System.nanoTime` 슬롯 CAS(:27-36), 대기 상한 초과 시 `onReject` 예외(:31-33), 인터럽트도 거절(:41-43). **판정: 그대로**(프로세스 로컬).

| 인스턴스(main) | file:line | 간격 | 대기 상한 | 키 | 거절 예외 |
|---|---|---|---|---|---|
| KIS | `TC/broker/adapter/out/kis/KisHttpClient.java:123` (`new CallIntervalGate(350, 20_000)`), 사용 `awaitAccountSlot` :192-195, 매 시도 전 :137(`executeWithRetry`)·401 복구 재시도 전 :152 | 350ms | 20s | `account.appKey()` | `KisApiException.rejected`(확정 거절) |
| 메리츠 | `TC/broker/adapter/out/meritz/MeritzHttpClient.java:44` (`new CallIntervalGate(110, 20_000)`, 주 생성자 :47-), 사용 `awaitSlot` :121(루프 안 매 시도)·:177-180 | 110ms | 20s | `account.appKey()` | `MeritzApiException.rejected` |
| 토스 | **없음** (`trading-core/.../broker/adapter/out/toss/*.java` 에 `CallIntervalGate` 참조 0건) | - | - | - | - |
(Upbit·Binance 어댑터도 게이트 없음 — 09 결론 유지. 테스트에서만 `new CallIntervalGate(0, 20_000)` 등.)
- 주문 POST 재시도: KIS `post()`→`executeWithRetry(trId, account, false, …)`(`KisHttpClient.java:75`, `retryOnRateLimit=false`), EGW00201 재시도 분기 `:161`(`retryOnRateLimit && isRateLimited && attempt<2`)는 GET만. 401 토큰 복구 후 1회 재전송은 POST도(:141-158). 메리츠 `post()`→`execute(…, idempotent=false)`(`MeritzHttpClient.java:75-82`), 한도 EGW00200은 `if (!idempotent || …) throw new BrokerRateLimitException()`(:133), 토큰 무효 1회 재발급·재전송은 POST 포함(:124-131). 토스: POST `TossHttpClient.java:88`→`executeWithRetry`(:109) = 401 토큰 재시도(`MAX_RETRY_ATTEMPTS=3`, :102)만, 429는 `BrokerRateLimitException`으로 그대로(:150-152), 429 재시도(`MAX_RATE_LIMIT_RETRIES=2`, :105)는 관리자 공용 GET(`retryOnTooManyRequests` :57-67)만. **세 벤더 모두 주문 POST는 한도 재시도 없음 → 09의 "한도 초과 시 배치 주문 접수 실패" 서술 그대로.**
- `TradingParallelRunner`(`TC/trading/application/service/support/TradingParallelRunner.java`): 생성자 `@Value("${app.trading.parallel-per-account:2}")`(:25, 기본 2, 0 이하면 순차 :35), 호출마다 지역 `Map<UUID,Semaphore> gates`(:37), `new Semaphore(maxConcurrentPerGroup)`(:43), groupKey=계좌 id. **프로세스 전역 상한이 아니라 `runAll` 호출 안 상한** — 판정 그대로. 사용처: 배치 `TradingCandidatePlanner.saveAllocatedOrders`(:252-262 `parallelRunner.runAll`) 등.

### 4. M3 — 토큰 코디네이터
| 클래스 | file:line | 현재 상태 | 사용 벤더 | 판정 |
|---|---|---|---|---|
| `DbTokenCoordinator` | `TC/broker/adapter/out/internal/DbTokenCoordinator.java:18` (`locks` ConcurrentMap<UUID,ReentrantLock> :21, `obtainLocked` :37-47, `recover` :50-56) | 계좌별 JVM 락 + DB `broker_tokens` 더블체크. 클래스 주석(:13-16) "재발급이 비파괴적이라 인스턴스 간 분산 조정이 불필요. 이 비대칭은 의도된 설계" | KIS(`KisAuthApi.java:53` 주입, getToken :61·recoverToken :66)·메리츠(`MeritzAuthApi.java:44`) | 그대로 |
| `KisAdminTokenCoordinator` | `TC/broker/adapter/out/kis/KisAdminTokenCoordinator.java` (`lock` ReentrantLock :31, `volatile MemoryToken memory` :33, `obtain` :42-53, `recover` :56-76) | Redis `kis:admin:token:{sha8}` 주 저장 + JVM 락 + 메모리 폴백 | KIS 관리자 시세 토큰(`KisAuthApi.java:56` 주입) | 그대로 |
| `TossDistributedTokenCoordinator` | `TC/broker/adapter/out/toss/TossDistributedTokenCoordinator.java` (+ `TossRedisTokenStore`) | Redis lease + fencing generation CAS, JVM 상태 없음 | 토스 계좌·admin(`TossAuthApi.java:31`, `TossHttpClient.java:137,174`, 무효화 `AccountTokenEvictionAdapter.java:44`) | 그대로 |
- `KisAuthApi.tempTokenCache`(`TC/broker/adapter/out/kis/KisAuthApi.java:46`, 90초 `:104`) 그대로 JVM-local(A4).
- 메리츠 토큰 발급 벤더 제한: `docs/agents/vendors/meritz.md:14`는 "만료 전 재발급은 같은 토큰·같은 `expires_date`를 돌려준다(멱등)", `oauth2/revoke` 호출 금지만 있고 **KIS식 "1분 1회"(EGW00133) 제한은 문서에 없다**. 오류표(:25)에도 없음. 반면 KIS는 `vendors/kis.md:22,24`와 `KisAuthApi.java:162-165`(EGW00133→`BrokerRateLimitException`)에 1분 1회가 있다. → M3의 "두 프로세스 동시 miss → 한쪽 실패"는 **KIS에만 해당**, 메리츠는 멱등이라 중복 발급 무해(문서 근거; 실측 확인은 미실행).

### 5. M4 — 이벤트 리스너·발행 지점·스트림 클래스 (R=root, T=trading-core)
09 이후 S5(0d23b822b) 예외 처리기 통합으로 **`TradingExceptionHandler`는 사라지고** 공용 `ProblemDetailExceptionHandler`(SH/platform/web) + 모듈별 `ExceptionReporter` 구현(`API/web/ApiExceptionReporter.java:13-19`(R), `TC/web/TradingExceptionReporter.java:19`(T))이 `AppErrorRaisedEvent`를 발행한다. 09의 `TradingExceptionHandler.java:115` 발행 지점은 **달라짐**.

### 리스너 전수
| 이벤트 | 프로세스 | 리스너 file:line | 애너테이션 | 하는 일 |
|---|---|---|---|---|
| `UserDeletedEvent` | R | `API/notify/adapter/out/gateway/UserDeletedNotifier.java:17-18` | `@TransactionalEventListener(AFTER_COMMIT)` | 관리자 통지 |
| | R | `API/notify/adapter/out/gateway/UserFcmCleanupListener.java:21-23` | `@TransactionalEventListener(AFTER_COMMIT)` | FCM 토큰 삭제 |
| | R | `API/finance/adapter/in/event/FinanceUserCascadeListener.java:21-23` | `@TransactionalEventListener(AFTER_COMMIT)` | 가계부 삭제 |
| | R | `API/user/adapter/out/redis/UserEventStreamPublisher.java:29-31` | `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` | `stream:user.deleted` XADD |
| | T | `TC/trading/adapter/in/event/TradingUserCascadeListener.java:25-26` | `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` | 열린 주문 취소·사용자 매매 데이터 삭제 |
| `UserNotifyProfileChangedEvent` | R | `UserEventStreamPublisher.java:35-37` | 위와 같음 | `stream:user.notify-profile.changed` XADD |
| | T | `TC/trading/adapter/in/event/UserNotifyProfileSyncListener.java:27-29` | `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` | `user_notify_profile` upsert |
| `AppErrorRaisedEvent` | R | `API/admin/adapter/in/event/AppErrorRaisedListener.java:20-21` | `@EventListener`(동기, 주석 :12) | `app_error_logs` 저장 |
| | T | `TC/trading/adapter/out/redis/AppErrorStreamPublisher.java:26-27` | `@EventListener` | `stream:app.error` XADD |
→ 09 C-1/C-2의 이중 기록(한 JVM에 합치면 R·T 두 리스너가 같은 이벤트를 듣는다)·`UserDeleted`/`NotifyProfile` 이중 실행 구조 **그대로**.

### 발행 지점 전수
| 이벤트 | 발행 file:line | 트랜잭션 안? |
|---|---|---|
| `UserDeletedEvent` | `API/user/application/service/UserCascadeDeleter.java:40` (호출 `UserService.java:199`) | 안 — `UserService` 클래스 레벨 `@Transactional`(UserService.java:34) 안, UserCascadeDeleter 자체는 애너테이션 없음 |
| `UserNotifyProfileChangedEvent` | `API/user/application/service/UserNotifyProfilePublisher.java:48` (호출: `UserService.java:120,132,146,178`, `UserSettingsService.java:40,47`, `AdminSeedPromoter.java:30`) | 대부분 안(`UserSettingsService.java:31,44,51` `@Transactional`, `AdminSeedPromoter.java:27` `@Transactional`, `UserService` 클래스 레벨). 예외: `UserService.login` 은 `NOT_SUPPORTED`(:66)이고 :120 호출이 transactionOperations 콜백 안이라 fallbackExecution 의존 |
| `AppErrorRaisedEvent` | R: `ApiExceptionReporter.java:18`, `API/notify/adapter/out/gateway/TelegramAdapter.java:26`; T: `TradingExceptionReporter.java:19`, `TC/notify/adapter/out/gateway/TradingNotifyAdapter.java:33` | 대부분 밖(예외 처리기·알림 어댑터) → 동기 `@EventListener`로 받는 이유 |
| (T 재발행) | `TC/trading/adapter/in/redis/UserEventRepublisher.java:22-28` `@Transactional` + `publishEvent` — 스트림에서 읽은 같은 클래스 이벤트를 **로컬 재발행** | 안 |

### 스트림 관련 클래스 (현재 file:line, 전부 존재)
| 클래스 | file:line | 비고 |
|---|---|---|
| `UserEventStreamPublisher` (R) | `API/user/adapter/out/redis/UserEventStreamPublisher.java:24` | :29,:35 리스너 |
| `UserEventStreamConsumer` (T 추상) | `TC/trading/adapter/in/redis/UserEventStreamConsumer.java` — `@EventListener(ApplicationReadyEvent)` :39, 복구 `@Scheduled(fixedDelay=5m)` :63, 게이트 없음 | 그룹 `RedisStreamConfig.TRADING_CONSUMER_GROUP`(:33) |
| `UserDeletedStreamConsumer` (T) | `.../UserDeletedStreamConsumer.java:13` | |
| `UserNotifyProfileStreamConsumer` (T) | `.../UserNotifyProfileStreamConsumer.java:14` | |
| `UserEventRepublisher` (T) | `.../UserEventRepublisher.java:17` | |
| `UserEventStreamBridge` (T) | `.../UserEventStreamBridge.java:22` (역직렬화 :28 UserDeleted, :33 NotifyProfile) | |
| `AppErrorStreamPublisher` (T) | `TC/trading/adapter/out/redis/AppErrorStreamPublisher.java:20` | |
| `AppErrorStreamConsumer` (R) | `API/admin/adapter/in/redis/AppErrorStreamConsumer.java:26` — `ApplicationReadyEvent` :40, `@Scheduled` 5분 :55, 게이트 없음 | 그룹 `ROOT_CONSUMER_GROUP`, 이름 "root" |
| `PushNotificationStreamConsumer` (R) | `API/notify/adapter/in/redis/PushNotificationStreamConsumer.java:25` — :39, :54, 게이트 없음 | "root-push" |
| `UserEventResubmitScheduler` (R) | `API/web/UserEventResubmitScheduler.java:28` `@ConditionalOnProperty(prefix="scheduler", name="enabled", matchIfMissing=true)` :27, `@Scheduled(fixedDelay=10m, initialDelay=10m)` :37 | 사용자 이벤트(:48) 한정 |
| `UserEventResubmitScheduler` (T) | `TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java:27` 같은 게이트 :26, `@Scheduled` :36 | 판정 전부 그대로 |

### 6. M5 / F절 — `@Scheduled` 전수 (게이트 원문: `@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)`)
`matchIfMissing=true` 그대로. yml: root `src/main/resources/application.yml:75-76` `scheduler.enabled: ${SCHEDULER_ENABLED:true}`, `application-prod.yml:21-22` `false`(주석 :20 "prod 기본은 API role"), `application-local.yml:15-16` `false`. **trading-core `application*.yml`에는 `scheduler` 키 없음**(grep 0건) → kista-trading은 compose `SCHEDULER_ENABLED: "true"`(`deploy/server/docker-compose.yml:94`)와 `matchIfMissing` 기본값에 의존. compose: kista-api :29 `"false"`, kista-scheduler :56 `"true"`, kista-trading :94 `"true"`. 판정: **그대로**.
| 프로세스 | 클래스 file:line(@Scheduled) | cron/주기 | 게이트(:line) | SchedulerLockService 락 이름 |
|---|---|---|---|---|
| T | `trading/adapter/in/schedule/TradingOpenScheduler.java:46` | `0 0 22 * * MON-FRI` KST | :28 | `trading-open`(`TradingBatchJob.OPEN.lockName()`, :52 `tryRun`·:58 `runNow`·:69 `takeOver`) TTL 2h |
| T | `TradingCloseScheduler.java:70`(reconcile), `:79`(하트비트), 동적 `taskScheduler.schedule` :111 | `0 5 * * * *`, `0 30 4 * * TUE-SAT`, 거래일별 마감 예약 | :38 | `trading-close`(:123 `tryRun`, runNow :143, resume/resumeReport `takeOver` :153·:160) TTL 3h. 예약은 메모리 `HashMap reservations` :53, `synchronized register` :91 |
| T | `trading/adapter/in/schedule/CryptoDcaScheduler.java:27` | `0 */10 * * * *` | :19 | `crypto-dca`(:29, 9분) |
| T | `trading/adapter/in/schedule/UserEventResubmitScheduler.java:36` | fixedDelay 10m, initialDelay 10m | :26 | **없음** |
| T | `account/adapter/in/schedule/ApiKeyExpiryScheduler.java:32` | `0 0 10 * * *` | :22 | `api-key-expiry`(:34, 30분) |
| T | `marketcalendar/adapter/in/schedule/MarketCalendarRefreshScheduler.java:53,64` | `0 0 0 1 1 *`, `0 0 1 1 * *` | :27 | `market-calendar-startup`(:40, 1h)·`market-calendar-yearly`(:55, 1h)·`market-calendar-monthly`(:66, 30분) |
| T | `trading/adapter/in/redis/UserEventStreamConsumer.java:63` | fixedDelay 5m | **없음** | 없음 |
| R | `API/benchmark/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java:24` | `0 0 8 * * SAT` | :17 | `kbland-housing-benchmark`(:26,:31) |
| R | `MarketIndexPriceSyncScheduler.java:26` | `0 0 9 * * *` | :16 | `market-index-price-sync`(:28,:33) |
| R | `KbLandPriceIndexScheduler.java:29,34` | `0 10 8 * * SAT`, `0 20 8 1 * *` | :19 | `kbland-price-index`(:31,:41)·`kbland-price-index-full`(:36,:47) |
| R | `API/user/adapter/in/schedule/RefreshTokenCleanupScheduler.java:25,35` | `0 0 4 * * *`, `0 5 3 * * *` | :17 | `refresh-token-expired-cleanup`(:27)·`refresh-token-rotated-cleanup`(:37) |
| R | `API/finance/adapter/in/schedule/FinanceRegistrationReminderScheduler.java:25` | `0 0 21 L * *` | :18 | `finance-registration-reminder`(:27) |
| R | `API/market/adapter/in/schedule/FearGreedScheduler.java:27` | `0 0 7,12 * * *` | :19 | `fear-greed`(:29) |
| R | `API/web/UserEventResubmitScheduler.java:37` | fixedDelay 10m | :27 | **없음** |
| R | `API/admin/adapter/in/redis/AppErrorStreamConsumer.java:55` | fixedDelay 5m 복구 | **없음** | 없음 |
| R | `API/notify/adapter/in/redis/PushNotificationStreamConsumer.java:54` | fixedDelay 5m 복구 | **없음** | 없음 |
- **게이트가 없는 `@Scheduled`: 3개**(`UserEventStreamConsumer:63`·`AppErrorStreamConsumer:55`·`PushNotificationStreamConsumer:54`). 09와 동일. `@Scheduled` 클래스 총 16개(T 7, R 9), 메서드 수 20(T 9, R 11).
- 락 없이 게이트만 있는 `UserEventResubmitScheduler` 2벌(주석: "운영 kista-trading 단일 인스턴스만" T:26).
- 스케줄러 외 `scheduler.enabled` 게이트 빈: `TradingBatchResumer.java:30`(+`@Profile("!local")` :31), `TradingSchedulerInternalController.java:21`(T, 수동 트리거), `API/web/AdminSchedulerController.java:26`(R). **달라짐(문서 드리프트)**: `deploy/server/docker-compose.yml:80`의 "TradingSchedulerInternalController(수동 트리거)가 scheduler.enabled 게이팅 없이 항상 노출돼"는 현재 코드와 다르다(`:21`에 게이트가 있음) — 09의 "조건부 빈이라 web에 없음"이 코드 기준으로 맞다.
- `TaskScheduler.schedule` 동적 예약: `TradingCloseScheduler.java:111` 하나뿐(`taskScheduler` 필드 :51).
- **`scheduler.enabled`/`prefix = "scheduler"` 읽는 곳 개수**: main `@ConditionalOnProperty` **16곳 = R 8 + T 8**(R: KbLandHousing·MarketIndexPriceSync·KbLandPriceIndex·RefreshTokenCleanup·FinanceRegistrationReminder·FearGreed·`web/UserEventResubmitScheduler`·`web/AdminSchedulerController`, T: MarketCalendarRefresh·ApiKeyExpiry·TradingBatchResumer·CryptoDca·TradingOpen·`UserEventResubmitScheduler`·TradingClose·TradingSchedulerInternalController). main yml 설정 3(root application/-prod/-local). test: 5파일 8회(R 4파일: `SchedulerRoleConfigTest`1·`SchedulerDisabledContextTest`4·`AdminTradingSchedulerControllerTest`1·`ArchitectureMapExporter`1, T 1파일: `CryptoDcaSchedulerTest`1) + `src/test/resources/architecture/flows.yml`. 배포·CI: `deploy/server/docker-compose.yml`, `.github/scripts/detect-deploy-scope.sh`, `.github/tests/deploy-plan.bats`.

### 7. E절 — 기동 시 동작
| 대상 file:line | 동작 | worker 전용이어야 하나 |
|---|---|---|
| `TC/trading/adapter/in/schedule/TradingBatchResumer.java:45` `@EventListener(ApplicationReadyEvent)` + `@Order(LOWEST)` :46, 게이트 :30, `@Profile("!local")` :31 | 미완료 배치 재개, `takeOver`(SchedulerLockService.java:32 "단일 인스턴스·비겹침 배포 전제" 주석 :29-31) | **예**(금지: web이 인수하면 worker 락을 덮어씀) |
| `TC/marketcalendar/.../MarketCalendarRefreshScheduler.java:37` `@EventListener(ApplicationReadyEvent)` + `@Order(HIGHEST)` :38, 게이트 :27, 락 `market-calendar-startup` | 기동 시 캘린더 적재(DB 쓰기) | 예(web은 읽기만) |
| `TC/trading/adapter/in/redis/UserEventStreamConsumer.java:39`, `API/admin/adapter/in/redis/AppErrorStreamConsumer.java:40`, `API/notify/adapter/in/redis/PushNotificationStreamConsumer.java:39` `@EventListener(ApplicationReadyEvent)` | Redis Stream 구독 시작(+5분 복구 `@Scheduled`), **게이트 없음** | 합친 뒤 삭제 대상. 그 전엔 competing consumer라 중복 처리 없음 |
| `API/notify/adapter/out/sse/RedisTradeEventSubscriber.java:28`, `API/benchmark/adapter/in/redis/TradeEventBenchmarkCacheInvalidator.java:27` `@PostConstruct` | Pub/Sub `trade.event` 구독 | **web 전부 필요**, worker 불필요(무해) |
| `TC/trading/application/service/support/TradingBatchShutdownCoordinator.java:16` `SmartLifecycle`(`running` :21, phase "웹서버 graceful보다 먼저" :51) | 종료 시 배치 임계구역 대기(≤150s) | 예(의미 없음 web) |
| `ApplicationRunner`·`CommandLineRunner`·`InitializingBean`·`SmartInitializingSingleton`·`ContextRefreshedEvent` | main 전체 grep **0건** | - |
`@PostConstruct` 실사용은 위 2곳뿐(나머지 3건은 주석). 판정 전부 **그대로**. (참고: 동기 `@EventListener`로 기동 후 DB를 쓰는 것은 위 두 곳.) 새로 확인한 `@EventListener` 일반 리스너: `API/notify/adapter/out/gateway/FinanceReminderChannelNotifier.java:19`(R).

### 8. G절 — Redis Stream·Pub/Sub·키
이름 상수: `SH/platform/redis/RedisStreamConfig.java:9-15`, `RedisPubSubConfig.java:15`. 판정 전부 **그대로**(클래스 존재 확인).
| 이름 | 종류 | 발행자 | 구독자 | 그룹 |
|---|---|---|---|---|
| `trade.event` | Pub/Sub | T `TC/notify/adapter/out/gateway/RedisTradeEventPublisher.java:25` `convertAndSend` | R `RedisTradeEventSubscriber.java:30`(SSE, 모든 web), R `TradeEventBenchmarkCacheInvalidator.java:29` | 없음(팬아웃) |
| `stream:user.deleted` | Stream | R `UserEventStreamPublisher.java:32` | T `UserDeletedStreamConsumer.java:19` → `UserEventRepublisher` | `trading-core`(RedisStreamConfig:14) |
| `stream:user.notify-profile.changed` | Stream | R `UserEventStreamPublisher.java:38` | T `UserNotifyProfileStreamConsumer.java:20` | `trading-core` |
| `stream:app.error` | Stream | T `AppErrorStreamPublisher.java:29` | R `AppErrorStreamConsumer.java:33` | `root`(:15) |
| `stream:user.push-notification.requested` | Stream | T `TC/notify/adapter/out/gateway/RedisPushNotificationPublisher.java:29` (09는 `RedisPushNotificationPublisher.java:21-29` 표기, 경로가 `trading/adapter/out/redis`가 아니라 `notify/adapter/out/gateway`) | R `PushNotificationStreamConsumer.java:32` | `root` |
| `kis:admin:token:{sha8}` | Key | T `KisAdminTokenCoordinator.java:26` | T | - |
| `toss:token:*` | Key | T `TossRedisTokenStore.java:20` | T | lease+fencing |
| JWT 블랙리스트 | Key | R `API/user/adapter/out/redis/RedisBlacklistAdapter.java:14`(extends `SH/platform/security/RedisTokenBlacklistReader.java:11`) | R·T | - |
- S9 연관: trading-core 쪽 `RedisBlacklistAdapter`는 사라졌고 reader는 platform 한 벌(f5e707675).

### 9. C절 EPR 설정 현재 값
| 위치 | 값 |
|---|---|
| root `src/main/resources/application.yml:39-46` | `spring.modulith.events.jdbc.schema: public`, `schema-initialization.enabled: false`, `republish-outstanding-events-on-restart: true`(:46) |
| root `application-prod.yml:13-18` | `republish-outstanding-events-on-restart: false`(주석 :15-17 "EPR 재발행 소유자 = kista-scheduler 단독") |
| trading-core `trading-core/src/main/resources/application.yml:39-45` | `detection-strategy: explicitly-annotated`, `events.jdbc.schema: trading`, `schema-initialization.enabled: false`. **`republish-outstanding-events-on-restart` 키 없음**(Modulith 기본값은 false) → compose env로만 켬 |
| `shared/src/testFixtures/resources/application-test.yml:8-12` | `events.jdbc.schema-initialization.enabled: false`(재발행 키 없음) |
| compose `deploy/server/docker-compose.yml` | kista-api: 없음(prod yml false 상속, SCHEDULER_ENABLED "false" :29), kista-scheduler `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"` :60, kista-trading 같은 키 `"true"` :96 |
| `.github/tests/compose-invariants.bats:16` | **kista-trading `"true"`만 단언**(+stop_grace_period ≥200s :14-15). kista-scheduler true·kista-api false는 이 bats가 단언하지 않는다(`constraints.md` "EPR 재발행 플래그는 kista-trading·kista-scheduler만 true(가드: compose-invariants.bats)"보다 가드가 좁음) |
판정: 09 C절 값 **그대로**.
- `@TransactionalEventListener` 리스너 수: **R 16개, T 20개**(파일별: R — `UserSeedPromotedAuditListener`1·`FinanceUserCascadeListener`1·`AlertNotifier`2·`CompositeUserNotificationAdapter`4·`SchedulerNotifier`1·`UserDeletedNotifier`1·`UserFcmCleanupListener`1·`SseEmitterRegistry`2·`ApprovalRequirementDisabledListener`1·`UserEventStreamPublisher`2; T — `ApiKeyExpiryNotifier`1·`CryptoTradeNotifier`1·`CycleEndedNotifier`1·`CycleLifecycleNotifier`2·`OrderCancelFailureNotifier`1·`PrivacyAlertNotifier`1·`TradingAlertNotifier`9·`TradingReportNotifier`1·`AccountCascadeListener`1·`TradingUserCascadeListener`1·`UserNotifyProfileSyncListener`1). `@ApplicationModuleListener`는 main 전체 **0개**. 일반 `@EventListener`(동기): R 4(`PushNotificationStreamConsumer:39`·`AppErrorStreamConsumer:40`는 ApplicationReady, `FinanceReminderChannelNotifier:19`, `AppErrorRaisedListener:20`), T 6(`MarketCalendarRefreshScheduler:37`·`TradingBatchResumer:45`·`UserEventStreamConsumer:39` ApplicationReady, `AppErrorStreamPublisher:26`, `TradingStatsService:104,113`).

### 10. I-2 S1~S9 재판정
| # | 현재 | 판정 |
|---|---|---|
| S1 통계 캐시 무효화 프로세스 간 미전달 | `TradingStatsService.java:57` 프로세스 로컬 `TtlResultCache`, 무효화 `@EventListener` :104(`TradingReportReadyEvent`)·:113(`StrategyCapitalChangedEvent`). 발행 지점: `StrategyService.java:63,81,190`, `CapitalLedgerService.java:105,168`, `StrategyUpdateWriter.java:82`, `VrReconfigureService.java:128`, `ManualTradeCorrectionService.java:114`, `CryptoOrderSynchronizer.java:126`, `AccountCascadeListener.java:37`, `TradingReporter.java:85` | 그대로 |
| S2 `tempTokenCache` JVM-local | `KisAuthApi.java:46`, put :104, get :118 | 그대로 |
| S3 `verifyAccount`가 게이트 우회 | `KisAuthApi.java:114` `verifyAccount`, :146 `kisRestClient.get()` 직접(게이트 없음) | 그대로 |
| S4 상태 SSE 이벤트 발행 인스턴스에만 | `SseEmitterRegistry.java:20` 로컬 맵, `@TransactionalEventListener` :43,:48 | 그대로 |
| S5 search_path·락 테이블 해석 | root `application.yml:19` `finance, kista_ref, public`, trading `application.yml:19` `trading, trading_ref`, `SchedulerLockService.java:61,77` 비한정 `scheduler_locks`, EPR schema 각각 `public`/`trading` | 그대로 |
| S6 스트림 4종 삭제·복구 `@Scheduled` 무게이트 | 컨슈머 3개 `@Scheduled` 무게이트(§6), 스트림 4종 존재(§8) | 그대로 |
| S7 `UserEventResubmitScheduler` 2벌·`alerted` | R `API/web/UserEventResubmitScheduler.java:28`, T `TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java:27`, `OrderSubmissionReconciler.java:44` `alerted` | 그대로 |
| S8 알림 시크릿 role 공통 | 세 서비스 모두 `env_file: - .env`(`docker-compose.yml:25,52,88`) | 그대로 |
| S9 블랙리스트 어댑터 2벌 | trading 쪽 `RedisBlacklistAdapter`가 사라지고 platform `RedisTokenBlacklistReader`(`SH/platform/security/RedisTokenBlacklistReader.java:11`) 하나 + root `RedisBlacklistAdapter extends`(`API/user/adapter/out/redis/RedisBlacklistAdapter.java:14`) | 해소됨(f5e707675, 2026-11-13) |

### 11. findings/09에 없는 새 프로세스 로컬 상태 (기준: 11-12 23:33 `b3e7dfcc3`..HEAD의 main 코드 추가 라인 grep)
| 파일:line | 상태 | 사용 경로 | web N + worker 1 영향 | 심각도 |
|---|---|---|---|---|
| `TC/broker/adapter/out/marketdata/AlpacaMarketDataAdapter.java:59` `TtlResultCache cache`(현재가 60s :46, 세션 확정 24h :47, 일봉 1h :48, 생성 :65). 주석 "시세를 읽는 프로세스는 하나" | 종목별 인메모리 캐시. Alpaca 호출에 간격 게이트·한도 재시도 없음(`withOneRetry` :207-214는 연결 실패·5xx 1회만) | 호출자는 `PriceSourceComparison`(`TradingService.java:120,160,299,358,442` — 배치=worker 전용) 뿐. `PriceSourceComparison.java:82` `Thread.sleep(closeRecheckDelay)` | 지금은 worker만 호출해 무해. 14 T3에서 web 경로(미리보기·전략 등록)가 이 어댑터를 쓰면 프로세스마다 캐시·호출 한도(Alpaca 키 단위)가 따로 | 낮음(현재) / 중간(T3 후) |
| `TC/trading/application/service/PriceSourceComparison.java:28-42` | 가변 필드 없음(`alpaca`·`brokerPricePort`·`closeRecheckDelay` final), 지역 `HashMap`만 | 배치 | 상태 없음 → 무해("임시, T3에서 삭제" 주석 `TradingService.java:58`) | 무해 |
| `TC/broker/adapter/out/binance/BinanceHttpClient.java:42` `volatile long serverOffsetMs`(5016551a0, -1021 시 `/api/v3/time` 보정) | 프로세스별 서버-로컬 시각 오프셋 | 바이낸스 호출 전체 | 프로세스마다 따로 보정(각자 -1021을 한 번 겪고 재서명). 값은 수렴, 부작용 작음. 바이낸스는 삭제 예정(15) | 무해 |
| `SH/platform/web/ProblemDetailExceptionHandler.java:32` `final Map responders`(생성 시 `merge` :36) | 불변 맵(기동 시 1회) | 모든 HTTP | 상태 변동 없음 → 무해 | 무해 |
그 외 11-12 이후 변경 파일 324개(대부분 S6 UseCase 제거·doc)에서 `ConcurrentHashMap|ReentrantLock|volatile|synchronized|Atomic|TtlResultCache|Semaphore` **새 필드 추가는 위 표 외 없음**(추가 라인 grep 기준).

### 1. A1~A19, B1~B11 행별 재판정 (기준 b3e7dfcc3(11-12 23:33)..HEAD 변경 이력 확인, 줄 번호는 현재)
대부분 파일이 11-12 이후 무변경(`git log b3e7dfcc3..HEAD -- <파일>` 공란). 변경된 것: DbTokenCoordinator·TossDistributedTokenCoordinator(7b8b9e9d2 문서/주석), TradingCandidatePlanner(3cdf3866b 주석), ManualTradingService(0d23b822b S5 예외), TradingCloseScheduler·CryptoDcaRunner·TradingStatsService·VrReconfigureService(UseCase 제거 19cc491d9·1bc1e453b), SchedulerLockService(2f0059277 주석), OrderPersistenceAdapter·StrategyCyclePersistenceAdapter(0cd178893 flush). 클래스 개명·삭제는 아래 명시한 것 외 없음.
| # | 클래스 현재 file:line | 판정 |
|---|---|---|
| A1 | `CallIntervalGate.java:12-47`(nextSlotByKey :16); KIS 인스턴스 `KisHttpClient.java:123`(350ms/20s/appKey), 메리츠 `MeritzHttpClient.java:44`(110ms/20s/appKey). 주문 POST `retryOnRateLimit=false`(`KisHttpClient.java:75`) | 그대로 |
| A2 | `DbTokenCoordinator.java:21`(locks), 주석 :10-14, 사용 `KisAuthApi.java:53,61,66`·`MeritzAuthApi.java:44`. 송신 전 거절 변환 `KisHttpClient.java:131-135` | 그대로(7b8b9e9d2는 주석만). 단 메리츠는 토큰 재발급 멱등이라 1분 1회 문제는 KIS 한정(§4) |
| A3 | `KisAdminTokenCoordinator.java:31`(lock), :33(volatile memory) | 그대로 |
| A4 | `KisAuthApi.java:46`(tempTokenCache), put :104 | 그대로 |
| A5 | `TossDistributedTokenCoordinator.java`·`TossRedisTokenStore.java:20` | 그대로 |
| A6 | `UsdKrwRateCache.java:19` | 그대로 |
| A7 | `TossPriceApi.java:48` | 그대로 |
| A8 | `TossCandleApi.java:43` | 그대로 |
| A9 | `AccountBudgetLock.java:17`(JVM ReentrantLock, :13 ponytail 주석 그대로). 사용처 `TradingCandidatePlanner.java:258`, `ManualTradingService.java:109`, `TradingOrderExecutor.java:122` | 달라짐(0cd178893: orders 슬롯 유일 제약 `uq_orders_live_slot` 생겨 같은 슬롯 이중 저장은 DB가 막고 위반은 저장 실패. 락은 여전히 JVM뿐. 사이클별 독립 예산이라 서로 다른 슬롯 초과 경로는 §2-d 참조) |
| A10 | `TradingBatchRunState.java:16-19`, `TradingBatchShutdownCoordinator.java:16`, `TradingBatchSerialExecutor.java:11-12`, 사용 `TradingService.java:77,144,157,183,234,258,330` | 그대로 |
| A11 | `TradingCloseScheduler.java:53`(reservations), :91(synchronized register) | 그대로(19cc491d9는 주입 필드명) |
| A12 | `TradingStatsService.java:57`(캐시), :104·:113(@EventListener), 발행 지점 §10 S1 | 그대로(줄만 이동) |
| A13 | `CryptoMarketIndicatorService.java:66`, `TossCandleApi.java:43` | 그대로 |
| A14 | `DcaPlanInputCollector.java:65,67,70` | 그대로 |
| A15 | `OrderSubmissionReconciler.java:44`(alerted), IN_FLIGHT_GRACE :36 | 그대로 |
| A16 | `CryptoDcaRunner.java:49` | 그대로 |
| A17 | `TradingParallelRunner.java:37,43`(기본 2 :25), `MarketEventNotifier.java:44` | 그대로 |
| A18 | `TtlResultCache.java:17`(cache·stripes·generation). 사용처 `TradingStatsService`, `CryptoMarketIndicatorService`, `TossCandleApi`, `BenchmarkService.java:34`, `FearGreedQueryService.java:39` + 신규 `AlpacaMarketDataAdapter.java:59` | 달라짐(사용처 1개 추가, §11) |
| A19 | `RedisStreamSubscriber.java:40-41`(container·subscription), `synchronized start` :73, `stop` :157 | 그대로 |
| B1 | = A9. 임계 구역 본문에 브로커 포트 호출 없음 재확인(§2-a). 이제 DB 유일 제약이 2차 방어 | 달라짐(A9와 동일) |
| B2 | = A2 | 그대로 |
| B3 | = A3 | 그대로 |
| B4 | = A1 | 그대로 |
| B5 | `TradingBatchSerialExecutor.java:12`, `TradingBatchRunState.java:16` | 그대로 |
| B6 | `TradingCloseScheduler.java:91`, `UsdKrwRateCache`, `TtlResultCache` stripes, `RedisStreamSubscriber:73,157` | 그대로 |
| B7 | `SchedulerLockService.java:22`(tryRun), `:33` forceAcquire 호출(takeOver), `:75` forceAcquire, 주석 :29-31, 비한정 `scheduler_locks` :61,77 | 그대로(2f0059277은 주석) |
| B8 | `StrategyCycleJpaRepository.java:19`(PESSIMISTIC_WRITE), 포트 `StrategyCyclePersistenceAdapter.java:34`. 사용 `BuyOrderPriceCapper.java:71`, `CapitalLedgerService.java:199`, `StrategyUpdateWriter.java:106`, `ManualTradeCorrectionService.java:70`, `CryptoLedgerRecorder.java:38` | 그대로 |
| B9 | `OrderPersistenceAdapter.java:121`(lockOrConflict), 사용 :57(markSubmitting)·:94(markPlaced)·:107(cancelPlanned)·:272(markFailed) | 달라짐(`cancelPlanned`가 `saveAndFlush` :112 — 0cd178893, 취소 후 같은 슬롯 재삽입 순서 보장) |
| B10 | `TossRedisTokenStore` lease+fencing | 그대로 |
| B11 | `VrReconfigureService.java:63`(reconfigure, 트랜잭션·락 없음), `OrderCancelService.java:50`(cancelByCycle) | 달라짐(0cd178893: `markEnded` flush :60-62 + `uq_strategy_cycle_open`으로 동시 재설정·롤오버가 열린 사이클 2개를 만들지 못하고 둘째가 저장 실패(미매핑 500). 락 부재 자체는 그대로) |

# 부록 B. `findings/10` 충돌 재측정 원본


### 2-a. 스테레오타입 빈 단순명 충돌 (R 153 / T 225 / S 18 = 396 클래스)

같은 단순명: `AlpacaConfig`, `AlpacaProperties`, `BinanceConfig`, `ClockConfig`, `UserEventResubmitScheduler` (5종).

| 이름 | 위치 | 기본 빈 이름 충돌 |
|---|---|---|
| ClockConfig | R/ClockConfig.java:13, T/ClockConfig.java:13 (둘 다 `@Configuration(proxyBeanMethods=false)`) | 충돌(+ `@Bean clock` 2개) |
| UserEventResubmitScheduler | R/web/UserEventResubmitScheduler.java:28 (`@Component`, `@ConditionalOnProperty` :27), T/trading/adapter/in/schedule/UserEventResubmitScheduler.java:27 | 충돌 |
| AlpacaConfig | R/benchmark/adapter/out/alpaca/AlpacaConfig.java(이름 없음→`alpacaConfig`), T/marketcalendar/.../AlpacaConfig.java:11 `"marketAlpacaConfig"`, T/stats/.../AlpacaConfig.java:13 `"tradingStatsAlpacaConfig"` | 없음 |
| BinanceConfig | T/broker/.../binance/BinanceConfig.java(`binanceConfig`), T/stats/.../binance/BinanceConfig.java:9 `"statsBinanceConfig"` (R에 없음) | 없음 |
| AlpacaProperties | R/benchmark/adapter/out/alpaca/AlpacaProperties.java(3필드), S/platform/alpaca/AlpacaProperties.java(4필드) — 둘 다 `prefix="alpaca"` | 빈 이름은 FQCN 기반이라 없음 |

- `RedisBlacklistAdapter` 쌍은 사라졌다: T 쪽 삭제, S `PlatformAutoConfiguration.java:~47`의 `@Bean @ConditionalOnMissingBean(TokenBlacklistPort) RedisTokenBlacklistReader`가 T용 읽기 빈을 올리고 R `user/adapter/out/redis/RedisBlacklistAdapter.java:14`가 `RedisTokenBlacklistReader`를 상속해 합병 뒤 후보 1개.
- T `AlpacaProperties` 2개(marketcalendar·stats)는 S 한 벌로 통합됨(S15, `f6d497105` 부근). 명시 이름 `@Configuration("...")` 8개는 T에만 있음.

### 2-b. `@Bean` 이름 중복

R 6개(alpacaRestClient, firebaseMessaging, clock, kbLandRestClient, openAPI, fearGreedRestClient, kakaoRestClient), S(filterChain, jwtFilterRegistration, internalFilterRegistration, internalApiRestClient, internalApiWriteRestClient, plainBigDecimalCustomizer, jwtDecoder, redisTokenBlacklistReader, processResidentMemoryGauge, telegramRestClient, telegramHttpClient, redisMessageListenerContainer), T(RestClient 16개 + 전략 빈 16개).
- **중복 이름은 `clock` 1건뿐**(R/ClockConfig.java:14, T/ClockConfig.java:14). 이전 B2 `fearGreedRestClient`는 T가 `tradingFearGreedRestClient`(T/trading/adapter/out/feargreed/TradingFearGreedConfig.java:14)로 개명되어 해소(`f5e707675`).
- RestClient 빈 총 23개(R 4 + S 3 + T 16), 이름은 전부 유일. T 신규: `marketDataAlpacaRestClient`(T/broker/adapter/out/marketdata/AlpacaMarketDataConfig.java:16, S15).

### 2-c. 타입 후보 2개 (신규 포함)

| 항목 | 현재 | 판정 |
|---|---|---|
| **`ExceptionReporter` (신규 발견)** | R/web/ApiExceptionReporter.java:12 `@Component`, T/web/TradingExceptionReporter.java:13 `@Component`. 소비처 S/platform/web/ProblemDetailExceptionHandler.java:104 `ObjectProvider<ExceptionReporter>.getIfAvailable(...)` | 합병 시 `NoUniqueBeanDefinitionException`. S5(`0d23b822b`)가 만든 충돌. 1개로 통합 필요 |
| `Clock` | 위 2-b | 충돌 |
| `TokenBlacklistPort` | 후보 1개로 해소 | 해소(`f5e707675`) |
| `SecurityFilterChain` | S/platform/security/SecurityConfig.java:39 `filterChain` 1개 | 그대로(공용 1개). 규칙: `/error`; `/actuator/**`; `/swagger-ui/**`,`/api-docs/**`,`/swagger-ui.html` permitAll → `/api/internal/**` hasRole(INTERNAL) → `/api/admin/**` hasRole(ADMIN) → `policies.orderedStream()` → `anyRequest().authenticated()` |
| `SecurityRoutePolicy` | R/web/RootSecurityPolicy.java:18 (permitAll `/telegram/webhook`, `POST /api/client-errors`, `POST /api/auth/kakao/callback|refresh|logout`, `POST /api/auth/dev-*`, `/api/auth/dev-approve/*`, `GET /api/market/**`, `GET /api/meta`, `GET /api/runtime-config`), T/web/TradingSecurityPolicy.java:16 (permitAll `GET /api/crypto-market-indicators`) | 경로 집합 겹침 없음. 둘 다 `ObjectProvider.orderedStream()`이라 안전 |
| `ProblemDetailMappingContributor`(WebMvcConfigurer 확장) | R 4(admin·user·finance·market), T 4(broker·privacy·account·trading) — `List`로 주입, 같은 예외 중복 기여는 기동 실패 | 그대로 안전(중복 예외 없음은 테스트로 고정) |
| `@RestControllerAdvice` | S/platform/web/ProblemDetailExceptionHandler 1개(`ProblemDetailAutoConfiguration`), R·T 개별 advice 삭제 | **해소됨(`0d23b822b`)** |
| `OpenAPI` | R/user/adapter/in/web/security/OpenApiConfig.java:18만 | 그대로 |
| `TaskScheduler`/`ObjectMapper`/`JsonMapper` | `@Bean` 없음(Boot 기본). `JsonMapperBuilderCustomizer plainBigDecimalCustomizer`(S/platform/PlatformJsonConfig.java:14) 1개 | 그대로. `PlainDecimalSerializer` 클래스는 현재 어디에도 없음 |
| `RedisMessageListenerContainer` | S 1개 | 그대로 |
| 같은 단순명 인터페이스 R↔T | 0건. S 인터페이스 구현: TokenBlacklistPort 1, ExceptionReporter 2, SecurityRoutePolicy 2, ProblemDetailMappingContributor 8 | ExceptionReporter만 충돌 |
| `@Primary` | R CompositeUserNotificationAdapter, T BrokerRouter | 그대로 |

### 2-d. `@ConfigurationProperties` prefix

`alpaca` ×2(R benchmark AlpacaProperties apiKey/apiSecret/dataBaseUrl, S AlpacaProperties baseUrl 포함 4필드; 이전 3개에서 감소), `telegram`(S), `internal.api`(S), `kakao`·`admin`·`kbland`(R), `heartbeat`·`cmc`(T). `kis.*`·`toss.*`·`meritz.*`·`upbit.*`·`binance.*`는 `@Value`. T `@ConfigurationPropertiesScan`은 TradingApplication.java:13 — 남은 T 소유 properties는 `HeartbeatProperties`, `CoinMarketCapProperties` 2개(T `AlpacaProperties` 2개는 S로 이동해 `@EnableConfigurationProperties`로 올림).

### 2-e. 같은 상대 경로 리소스

main: `application.yml`, `application-prod.yml`, `application-local.yml`(gitignore) — R·T 3쌍, S main은 `META-INF/spring/*.imports` 2개뿐(충돌 없음). logback* 없음. test: `archunit.properties`(R·T 각 1, 내용 `archRule.failOnEmptyShould=true`) 1쌍. db는 `db/migration`·`db/migration-trading`으로 경로 달라 충돌 없음(디렉토리 CLAUDE.md 없음).

### 2-f. yml 키 diff (leaf 키 기준, 파서 `tools/yml.py`)

| 파일 | R leaf | T leaf | 값 같음 | 값 다름 | R만 | T만 |
|---|---|---|---|---|---|---|
| application.yml | 57 | 54 | 30 | 10 | 17 | 14 |
| application-prod.yml | 17 | 16 | 15 | 0 | 2 | 1 |
| application-local.yml(키만) | 24 | 19 | 12 | 1 | 11 | 6 |

값 다른 키 11개 전부 findings/10 B-1에 있음: spring.application.name(kista-api/kista-trading), hikari.pool-name, hikari.connection-init-sql(`finance, kista_ref, public` / `trading, trading_ref`), flyway.locations·table·default-schema, modulith.events.jdbc.schema(public/trading), spring.data.redis.url(R 기본값 있음, T 없음), server.port(8080/`${SERVER_PORT:8081}`), cors.allowed-origins(R 필수/T 기본 localhost:3000), local `logging.file.name`.

한쪽에만 있는 키(prod 기준 포함) 중 B-1·B-2에 **없는 키 6개**:
| 키 | 위치 | 값 |
|---|---|---|
| logging.level.root | R base만 | INFO |
| logging.level.com.kista | R base만 | DEBUG (T base에 logging 블록 없음. prod는 양쪽 WARN/INFO로 동일) |
| logging.level.org.hibernate.SQL | R local만 | DEBUG |
| logging.level.org.hibernate.orm.jdbc.bind | R local만 | TRACE |
| spring.jpa.show-sql | R local만 | true |
| spring.jpa.hibernate.ddl-auto | R local만 | validate (base와 동일 값) |

B-1에 있는 한쪽 키(값 확인): R만 scheduler.enabled(`${SCHEDULER_ENABLED:true}`, prod·local false), modulith.republish(base true, prod false), spring.task.scheduling.thread-name-prefix, management exposure·show-details·metrics.tags, server.shutdown, app.cookie.*, kakao.*, admin.kakao-ids, firebase, kbland, internal.api.base-url(`${INTERNAL_API_BASE_URL:http://localhost:8081}`); T만 springdoc.paths-to-exclude(`/api/internal/**`), alpaca.base-url, kis.*(3), toss.*(3), meritz.base-url, upbit.base-url, heartbeat.*(3), cmc.api-key. T local `internal.api.base-url=http://localhost:8080`, `server.port=8081`, R local `scheduler.enabled=false`, T local scheduler 키 없음(켜짐).
- **KIS 키 3개**(kis.base-url, kis.admin-app-key, kis.admin-app-secret — T application.yml:86-89), **코인 키 3개**(upbit.base-url :100, heartbeat.crypto-url :109, cmc.api-key :118) + `binance.base-url`은 yml 없이 `@Value` 기본값만. `.env.example`에는 KIS·UPBIT·BINANCE·CMC 변수 없음(HEARTBEAT_* 만 있음).
- `T application.yml`의 `internal.api.base-url` 없음, `.env.example`에 `HEARTBEAT_CRYPTO_URL` 존재.

### 2-g. 엔티티·리포지토리

- `@Entity` R 21 / T 21(합 42), 단순명 중복 0, `*JpaRepository` R 21 / T 20, 단순명 중복 0. `@Table` 중 한 줄 파서에서 schema가 잡힌 37개(finance 9, public 8, trading 17, trading_ref 3), 나머지 5개는 `@Table(` 여러 줄 선언이라 파서 미확인(findings/10의 42/42 명시와 모순 근거 없음 — 별도 개별 확인은 안 함).
- 같은 테이블 두 엔티티: 없음. 같은 이름 테이블이 두 스키마에: `scheduler_locks`, `event_publication`(R V1__init.sql:300·345 public, T V1__init.sql:77·126 trading), 엔티티 없이 직접 SQL.

### 2-h. Flyway

- R·T yml `spring.flyway.*` (application.yml:31-37 양쪽): enabled true, validate-on-migrate true, baseline-on-migrate false, R `locations classpath:db/migration`/`table flyway_schema_history_api`/`default-schema public`, T `db/migration-trading`/`flyway_schema_history_trading`/`trading`.
- 최신 번호: R V6(`V6__user_settings_balance_check_enabled_drop.sql`, 6개), T V21(`V21__ledger_unique_constraints.sql`, 21개; findings/10 때는 V20). `Flyway` `@Bean` 0개(순수 프로퍼티).
- `SPRING_FLYWAY_BASELINE_ON_MIGRATE` 참조: 양쪽 application.yml:35 주석뿐. 그 주석이 가리키는 RUNBOOK(`deploy/server/schema-reorg/`)은 삭제됨.
- `DataJpaTestBase`(shared/src/testFixtures/.../DataJpaTestBase.java): `@DataJpaTest` + `@Import(TestJpaAuditingConfig, AesCryptoService, FixedClockTestConfig)`, Flyway 구성 없음, `application-test.yml`에도 flyway 키 없음. `RootDataJpaTestBase`(src/test/.../web/): 빈 껍데기, 상속 테스트 19개.

### 2-i. Modulith

- R 모듈 7: admin, benchmark, finance, market, notify, user, web(`id="web"`, CLOSED). T 모듈 9: account, broker, marketcalendar, matching, notify, privacy, stats, trading, web(`id="web"`, CLOSED). S 3: kernel, contract, platform(전부 OPEN). 같은 ID: `notify`, `web`(그대로).
- `@Modulithic(additionalPackages="com.kista.shared")`: R/ApiApplication.java:11, T/TradingApplication.java:12. `@EnableScheduling` 양쪽. T만 `@ConfigurationPropertiesScan`.
- `spring.modulith.*`: yml 양쪽 `detection-strategy explicitly-annotated`, `events.jdbc.schema`(public/trading), `schema-initialization.enabled false`; R만 `republish-outstanding-events-on-restart`(base true, prod false). `application-test.yml:8`는 schema-initialization false만.
- `ModulithArchitectureTest`: `src/test/java/com/kista/api/architecture/ModulithArchitectureTest.java`(`containsExactlyInAnyOrder` 10개 ID, Documenter 사용), `trading-core/src/test/java/com/kista/tradingcore/architecture/ModulithArchitectureTest.java`(12개 ID).
- `@NamedInterface` 선언 package-info 54줄(findings/10은 62).

### 2-j. 경로 (R·T 컨트롤러 55개, 핸들러 R 106 / T 71)

**같은 메서드+경로 중복: 0건**(정규화 `{}` 비교). 겹치는 prefix는 `/api/stats`뿐(R BenchmarkController `housing-benchmark/*` 5, T TradingStatsController `summary|equity-curve|cycles` 3). 같은 R 내 `/api/admin/scheduler`는 R/web/AdminSchedulerController(4)와 R/admin/.../AdminTradingSchedulerController(2)가 나눔(경로 다름).

R(모듈, 클래스, base): admin — AdminAccountController `/api/admin/accounts`, AdminDashboardController `/api/admin/dashboard`, AdminObservabilityController `/api/admin/logs`, AdminPingController `/api/admin`, AdminPrivacyTradeController `/api/admin/privacy-trade-bases`, AdminSettingsController `/api/admin/settings`, AdminTradeController `/api/admin`, AdminTradingSchedulerController `/api/admin/scheduler`, AdminUserController `/api/admin/users`, ClientErrorLogController `/api/client-errors`, RuntimeConfigController(메서드 경로), TelegramWebhookController(메서드 경로); benchmark — BenchmarkController `/api/stats`; finance — AdminFinanceCategoryController `/api/admin/finance/categories`, AssetSnapshotController `/api/finance/asset-snapshots`, FinanceAccountController `/api/finance/accounts`, FinanceBudgetController `/budgets`, FinanceBulkController `/bulk-register`, FinanceCategoryController `/categories`, FinanceGroupController `/api/finance`, FinanceTransactionController `/transactions`, MonthlyClosingController `/monthly-closings`; market — FearGreedController·MarketHolidayController `/api/market`; notify — FcmController `/api/fcm`, StatusStreamController `/api/auth`, TradeStreamController `/api/trades`; user — AuthController·DevAuthController(`@Profile("local")`) `/api/auth`, SettingsController `/api/settings`; web — AdminSchedulerController `/api/admin/scheduler`(`@ConditionalOnProperty` scheduler.enabled), MetaController `/api/meta`.
T: account — AccountController `/api/accounts`, AccountInternalController `/api/internal/accounts`; broker — CandleInternalController `/api/internal/broker/candles`; marketcalendar — MarketCalendarInternalController `/api/internal/marketcalendar`; privacy — FidaOrderController `/api/internal`, PrivacyBaseInternalController `/api/internal/privacy/trade-bases`, PrivacyInternalQueryController `/api/internal/privacy`; stats — BacktestController `/api/backtest`, CryptoMarketIndicatorController·CryptoPortfolioController(메서드 경로), DashboardController `/api`, PortfolioQueryInternalController, StatisticsController `/api/accounts/{accountId}`, TradingStatsController `/api/stats`, TradingStatsInternalController; trading — AccountLedgerTotalsController·OrderCancelController·TradingCycleController(메서드 경로 `/api/trading-cycles`, `/api/accounts/{id}/trading-cycles`), StrategyStatusInternalController, TradingInternalCommandController, TradingInternalQueryController, TradingPolicyInternalController, TradingSchedulerInternalController(`@ConditionalOnProperty` scheduler.enabled).
T `/api/internal/**` 핸들러 30개(어노테이션 수 기준), R 호출 어댑터 11개 그대로(`.../adapter/out/internal/`). `/api/internal/marketcalendar/is-open`은 R 호출자 없음 그대로.

### 2-l. `scheduler_locks`

- `S/platform/scheduling/SchedulerLockService.java` SQL(:60-108): `INSERT INTO scheduler_locks (name, lock_until, locked_at, locked_by, finished_at) ... ON CONFLICT (name) DO UPDATE ... WHERE scheduler_locks.lock_until <= now()`, 두 번째 INSERT는 `OR scheduler_locks.locked_by <> EXCLUDED.locked_by`(takeOver), `UPDATE scheduler_locks ...` 2개. **스키마 한정 없음**(search_path 의존). 테이블 이름 프로퍼티화는 없음(P3 미착수).
- 락 이름 R: kbland-housing-benchmark, kbland-price-index, kbland-price-index-full, market-index-price-sync, refresh-token-expired-cleanup, refresh-token-rotated-cleanup, finance-registration-reminder, fear-greed. T: market-calendar-startup, market-calendar-yearly, market-calendar-monthly, api-key-expiry, trading-open, trading-close(`TradingBatchJob.lockName`), crypto-dca. **겹침 0**. 둘 다 `finished_at` 컬럼 보유(R V2, T V3).

### 3. 남은 선행 작업 범위

#### P3 (SQL 스키마 한정)
- `nativeQuery=true`: R 16(10개 파일) / T 39(10개 파일) / S 0. 파싱한 55개 모두 FROM/JOIN/INTO/UPDATE 뒤에 스키마 없는 테이블이 있고, **스키마 한정 쿼리는 0개**.
- `JdbcTemplate` SQL main 4파일: R housing_benchmark_prices·housing_price_indices(`HousingBenchmarkPricePersistenceAdapter.java:27`, `HousingPriceIndexPersistenceAdapter.java:28`, kista_ref), T `TradingBatchRunPersistenceAdapter.java:28,36,47,54`(trading_batch_run·trading_batch_report), S `SchedulerLockService.java:60-108`(4 문장). `NamedParameterJdbcTemplate`·`createNativeQuery` 0. 전부 무한정.
- KIS·코인 전용 파일에 있는 JdbcTemplate/native SQL: 0개(해당 파일이 영속 어댑터가 아님). 단 `StrategyJpaRepository.java:44-58`이 `user_notify_profile` 조인(X6).
- 신설 가드·`kista.scheduler-lock.table` 프로퍼티: 없음.

#### P4 (role 게이트)
- `@ConditionalOnProperty(prefix="scheduler")` 16개: R 8(MarketIndexPriceSync, KbLandPriceIndex, KbLandHousingBenchmark, AdminSchedulerController, UserEventResubmit, RefreshTokenCleanup, FinanceRegistrationReminder, FearGreed), T 8(MarketCalendarRefresh, ApiKeyExpiry, TradingBatchResumer, CryptoDca, TradingOpen, UserEventResubmit, TradingClose, TradingSchedulerInternalController). 그 외 `@ConditionalOnProperty`: S `InternalApiClientConfig.java:22`(`internal.api.base-url`).
- 게이트 없는 `@Scheduled` 클래스 3개: R `PushNotificationStreamConsumer`, R `AdminAppErrorStreamConsumer`(실제 이름 `AppErrorStreamConsumer`), T `UserEventStreamConsumer`(스트림 복구용). 게이트 없는 R `AdminTradingSchedulerController`(컨트롤러). `kista.role`·`@ConditionalOnWorkerRole` 참조 0건. `scheduler.enabled` 언급 파일: README, deploy/server/docker-compose.yml, docs 7, `scripts/dev-seed/seed.sh`, `.github/scripts/detect-deploy-scope.sh`, 테스트 4(`SchedulerRoleConfigTest`, `SchedulerDisabledContextTest`, `AdminTradingSchedulerControllerTest`, `ArchitectureMapExporter`), flows.yml.

#### P6
`AppErrorStreamPublisher`: 클래스 T/trading/adapter/out/redis/AppErrorStreamPublisher.java + 테스트 1, 주석 참조 T/notify/.../TradingNotifyAdapter.java·T/web/TradingExceptionReporter.java. `AppErrorStreamConsumer`: R/admin/adapter/in/redis/AppErrorStreamConsumer.java + 테스트 1, 주석 R/admin/package-info.java·AppErrorRaisedListener.java. 문서: docs/architecture-map.md, scheduler-time-table.md, modules/tradingcore/web.md, modules/api/admin.md, flows.yml. 스트림 상수 `RedisStreamConfig.APP_ERROR_STREAM`(S).

#### 04 단계 A (KIS)
- KIS 전용 파일 27개: main java 15개 1,766줄(T/broker/adapter/out/kis 11 + persistence `KisToken{Entity,JpaRepository,PersistenceAdapter}` 3 + domain/model/kis/KisApiException 1), test java 10개 1,904줄(adapter/out/kis 9 + KisTokenPersistenceAdapterTest), md 2개 114줄(`docs/agents/vendors/kis.md`, 어댑터 CLAUDE.md). 합 3,784줄.
- KIS 전용 yml 키 3(위). 빈: `@Component` 10 + `@Configuration` KisConfig 1 + `@Bean kisRestClient`(KisConfig.java:16). KisTokenEntity는 `trading.broker_tokens`를 매핑하는데 그 테이블은 DbTokenCoordinator(메리츠)도 사용.
- KIS 언급이 있는 다른 파일(주석 포함): main·test·shared 합 161개(전부 `Broker.KIS` enum·주석·`StrategyTicker`·픽스처).
- KIS가 걸린 가드 규칙: `HexagonalArchitectureTest.java:61`(벤더 패키지 목록의 `domain.model.kis..`), `:143-154`(UsTradeDates allowlist에서 KisTradingApi·KisPriceApi 2개 FQCN, DisplayName "7개"), `:267-269`(벤더 모델 유출 주석), `:512-519`(`kis_parser_must_not_depend_on_application_layer` — `failOnEmptyShould=true`라 kis 패키지 삭제 시 실패), `TradingCoreTestVendorModelTest.java:27,34`(`domain.model.kis..`). `Broker.KIS`(shared Broker.java:12, `supportsOpenAuctionOrders` :41 `this == KIS`).

#### 15 (코인 삭제)
- 이름·디렉토리 기준 코인 전용: main java 68개 3,967줄, test java 35개 5,257줄(findings/20의 73/4,015·34/5,194와 근사; UseCase 인터페이스 제거로 소폭 감소). 분포 main: broker 18, stats 36, trading 9, notify 1, shared 1(`CryptoTradingRules`), R market 3(`CryptoFearGreedAdapter`·`Port`·`History`).
- yml 키 3(upbit.base-url, heartbeat.crypto-url, cmc.api-key) + `@Value` binance.base-url. 스테레오타입 빈 34개 + `@Bean` RestClient 7(binanceRestClient, upbitRestClient, statsBinance/Upbit/Coinbase/CoinMarketCap/Mempool RestClient). 스케줄러 1(`CryptoDcaScheduler`, 락 `crypto-dca`, heartbeat crypto-url). 컨트롤러 2개/엔드포인트 3개(`GET /api/crypto-market-indicators`, `/api/accounts/{id}/crypto-portfolio`, `/crypto-prices`). Redis 채널·스트림에 코인 전용 없음(코인 체결은 Spring 이벤트 `CryptoOrderFilledEvent` → `CryptoTradeNotifier`). 마이그레이션 `V10__account_upbit.sql`(T), `V5__fear_greed_crypto_snapshots_delete.sql`(R)은 적용분이라 수정 금지.

#### 교차표 (KIS·코인 코드가 걸린 충돌 항목)
| 항목 | KIS | 코인 |
|---|---|---|
| 2a 단순명 충돌 | 0 | `BinanceConfig` ×2(T 내부, R 충돌 없음) |
| 2b @Bean 이름 | `kisRestClient` 1(중복 아님) | RestClient 7(중복 아님) |
| 2c 타입 후보 | 0 | `TradingSecurityPolicy`는 코인 경로 1개만 기여 → 코인 삭제 시 클래스 소멸 |
| 2d prefix | 0 | `cmc` 1(`CoinMarketCapProperties`) |
| 2f yml 키(양쪽 합) | 3 | 3 |
| 2j 경로 | 0 | 컨트롤러 2·엔드포인트 3, Caddy `@trading` regex `crypto-market-indicators`, `CaddyRoutingTest` |
| 2l 락 이름 | 0 | `crypto-dca` 1 |
| 2g/2h DB | `broker_tokens` 엔티티 `KisTokenEntity`(테이블은 공유) | 엔티티 없음(계정·전략 행 데이터만, 15 4절) |
| 가드 | 5곳(위) | `Broker.UPBIT/BINANCE`·`TradingPolicySettings` 등 enum 참조 |

### 1. 판정이 바뀐 findings/10 항목만

| 항목 | 판정 |
|---|---|
| A4 RedisBlacklistAdapter | 해소됨(`f5e707675`) |
| A1·A4(AlpacaProperties 3→2) | 달라짐 — T 두 클래스가 S 한 벌로(prefix `alpaca` 바인딩 클래스 R 1 + S 1) |
| B2 fearGreedRestClient | 해소됨(`f5e707675`, T `tradingFearGreedRestClient`) |
| G-1 `@RestControllerAdvice` 2개 | 해소됨(`0d23b822b`). 대신 신규 `ExceptionReporter` 후보 2개 충돌 |
| E-3 `SchedulerLifecycleEvent` 알림 | 해소됨(`1260139b8`, `SchedulerNotifier.TRADING_JOBS` 8개 이름 필터) |
| E-3 `AppErrorRaisedEvent`·`UserDeletedEvent`·`UserNotifyProfileChangedEvent` 이중 청취 | 그대로(각각 R·T 리스너 존재) |
| G-3 Jackson `PlainDecimalSerializer` 2벌 | 달라짐 — 클래스 소멸, `PlatformJsonConfig.plainBigDecimalCustomizer`로 대체 |
| D 최신 번호 | 달라짐 — T V20 → V21(S11) |
| H-3 복제본 호출처 | 달라짐 — 호출 파일 15개(`TradingUserProfilePort`·`TradingUserProfiles` 포함 목록) |
| I-2 contract | 달라짐 — 타입 19개(package-info 8 포함 27파일) |
| J-1 `gson` | 달라짐 — 루트 의존에서 삭제(`74d59302e`) |
| K-1 `deploy/server/readiness` | 달라짐 — 디렉토리 없음. `schema-reorg` RUNBOOK도 없음 |
| A-1 A3 ClockConfig·A5 UserEventResubmitScheduler·A-2 B1 clock | 그대로 |
| B-1 yml 키 값 차이 | 그대로(11개, 전부 표에 있음), 표에 없는 키 6개 신규 확인 |
| C1·C2 search_path·락·EPR 테이블 | 그대로(native 55개·JdbcTemplate 10문장 전부 무한정) |
| D-2 Flyway·E-1 모듈 ID 충돌 `notify`·`web`·F 보안·G-2 경로 0건·H-1·H-2 | 그대로 |

# 부록 C. M 목록 재계수 원본

### M1 build/bootJar/Docker/CI
| 항목 | 지금 값 (file:line) | 차이/비고 |
|---|---|---|
| settings.gradle.kts | `include("trading-core")`, `include("shared")` (:3-4) | 일치 |
| root build.gradle.kts | `implementation(project(":shared"))` :35; `testImplementation(project(":trading-core"))` :78 (주석: main은 trading-core 비의존); `testImplementation(testFixtures(project(":shared")))` :77; `bootJar{archiveFileName="app.jar"}` :102-104 | `implementation(project(":trading-core"))` 없음 = M1이 추가 |
| trading-core/build.gradle.kts | plugins spring.boot+dependency.mgmt :4-5; `implementation(project(":shared"))` :34; `bootJar{archiveFileName="trading-core.jar"}` :79-81; test `workingDir=rootProject.projectDir` :84 | bootJar 비활성+jar 활성 필요: spring.boot 플러그인 유지 시 `bootJar.enabled=false`, `jar.enabled=true` |
| shared/build.gradle.kts | `bootJar` 설정이 :80에 있음(지침서 미언급) | shared도 boot 플러그인 보유 — M1에서 같이 확인 |
| Dockerfile | :11 `COPY trading-core/build.gradle.kts`; :15-16 주석; :20 `RUN ./gradlew clean bootJar :trading-core:bootJar --no-daemon -x test`; :30 `COPY ... app.jar`; :31 `COPY .../trading-core.jar trading-core.jar`; :46 `ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar ${APP_JAR:-app.jar}"]` | `APP_JAR` 설정처: deploy/server/docker-compose.yml:91 (`APP_JAR: trading-core.jar`) 하나 |
| CI jar 빌드 | workflow는 jar를 직접 빌드 안 함. server-deploy.yml:161-171 `docker/build-push-action`(context ., linux/arm64, tag ghcr SHA 1개)이 Dockerfile 호출. 이미지 1개(SHA 태그)를 3 role이 공유 | "jar 2개 푸시 단계" 없음 — 이미지 1개. ci.yml:70 `./gradlew test`, :75 `./gradlew integration` |
| detect-deploy-scope.sh | :6-8 주석 api/scheduler/trading 분류, :25 `trading-core.jar 전용`, :35 `그 외 app.jar` | M1/M10 때 분류 규칙 재작성 대상 (아래 배포 절) |
| GradleModuleBoundaryTest (build 파일 검사 규칙) | **build.gradle.kts 텍스트를 읽는 규칙 없음.** classfile 기반 5개 @Test + 1개 @ParameterizedTest: tradingCoreMustNotDependOnApiOnlyModules :19, tradingCoreTestsMustNotDependOnApiOnlyModules :36, sharedMustNotDependOnTradingCoreOrApiModules :55, schedulerRoleOnlyPackageMustNotBeReferencedFromOutside :73-74(@Test)/:84(AdminSchedulerController FQCN 예외), tradingCoreMustNotCallRootInternalApi :94, subprojectClassesStayUnderOwnRootPackage :120 (CSV 8행 :112-119), tradingCoreTestClassesStayUnderOwnRootPackage :130 (:135 `com.kista.shared.platform.scheduling..` 예외) | **지침서 M12가 말한 "R → T 금지" 규칙은 이 파일에 없다**(R main→T 방향 규칙 0건; 단지 testImplementation만 허용). 지울 규칙: :84 AdminSchedulerController 예외는 P4와, :94 tradingCoreMustNotCallRootInternalApi는 X8까지 유지 |

### M2 앱 클래스
- `src/main/java/com/kista/api/ApiApplication.java:10-13`: `@SpringBootApplication @Modulithic(additionalPackages="com.kista.shared") @EnableScheduling` (기본 스캔, scanBasePackages 없음, `@ConfigurationPropertiesScan` 없음, `@EntityScan`/`@EnableJpaRepositories` 없음).
- `trading-core/.../TradingApplication.java:10-14`: `@SpringBootApplication @Modulithic(additionalPackages="com.kista.shared") @ConfigurationPropertiesScan @EnableScheduling`.
- 차이: 지침서 "ApiApplication에 @ConfigurationPropertiesScan 두 패키지 명시" — R에는 현재 `@ConfigurationPropertiesScan`이 없음(R properties는 다른 방식 등록 중, 합치면 T 것이 필요해 추가). `@EntityScan`·`@EnableJpaRepositories`는 어디에도 없음(기본 스캔에 의존) → M2에서 새로 추가해야 함.
- `@EnableJpaAuditing`: `shared/.../platform/persistence/JpaAuditingConfig.java:8`(PlatformAutoConfiguration 경유) + 테스트 `DataJpaTestBase.java:20`. `@EnableScheduling`: ApiApplication, TradingApplication 각 1곳(합치면 1곳으로).
- `PlatformAutoConfiguration` 등록: `shared/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` = `com.kista.shared.platform.PlatformAutoConfiguration`, `com.kista.shared.platform.web.ProblemDetailAutoConfiguration`. 별도 `...webmvc.test.autoconfigure.AutoConfigureWebMvc.imports`에 ProblemDetailAutoConfiguration. 가드: GradleModuleBoundaryTest :112-119 루트 패키지 규칙.

### M5 Flyway
- `Flyway` @Bean / `FlywayMigrationStrategy` / `Flyway.configure` / `FlywayConfigurationCustomizer`: Java·kts에서 **0건**. 설정은 yml뿐: `src/main/resources/application.yml:31-34`(`flyway:` table `flyway_schema_history_api`), `trading-core/src/main/resources/application.yml:31-34`(table `flyway_schema_history_trading`).
- baseline env 계약 `SPRING_FLYWAY_BASELINE_ON_MIGRATE`는 아직 yml/Java에 안 보임(별도 확인 필요: docker-infra.md 등 문서). 

### M6 scheduler_locks
- `shared/src/main/java/com/kista/shared/platform/scheduling/SchedulerLockService.java:17`(class), `tryRun` :22, `takeOver` :32, `LockedTask` :126. SQL은 **스키마 미한정** `INSERT INTO scheduler_locks` :61,:77, `UPDATE scheduler_locks` :96,:108, `scheduler_locks.lock_until` :68,:84,:85. → P3 미착수 확인(13절과 일치).
- `kista.scheduler-lock.*` 프로퍼티: **0건**(P3 미착수).
- DDL: R `src/main/resources/db/migration/V1__init.sql:345` `public.scheduler_locks`(+pkey :618, idx :826) + `V2__scheduler_locks_finished_at.sql:4`; T `trading-core/src/main/resources/db/migration-trading/V1__init.sql:126` `trading.scheduler_locks`(+pkey :349, idx :516) + `V3__scheduler_locks_finished_at.sql:4`.
- 락 이름 전수(겹침 0): R(8) kbland-housing-benchmark, kbland-price-index, kbland-price-index-full, market-index-price-sync, finance-registration-reminder, fear-greed, refresh-token-expired-cleanup, refresh-token-rotated-cleanup. T(7) api-key-expiry, market-calendar-startup, market-calendar-yearly, market-calendar-monthly, crypto-dca, trading-close, trading-open (`TradingBatchJob.java` CLOSE/OPEN; `trading_batch_run.job_name`이 같은 값을 씀 — TradingBatchRunPersistenceAdapter:29,41).
- 테스트: `trading-core/src/test/java/com/kista/shared/platform/scheduling/SchedulerLockService{,Db}Test.java`(DataJpaTestBase 상속, unqualified SQL :26,:33,:43,:49,:70).
- `scheduler.enabled` 게이트(@ConditionalOnProperty prefix=scheduler) 현재: R 8클래스(Scheduler 6 + AdminSchedulerController + web/UserEventResubmitScheduler), T 9클래스(ApiKeyExpiry, MarketCalendarRefresh, CryptoDca, TradingBatchResumer, TradingClose, TradingOpen, UserEventResubmitScheduler, TradingSchedulerInternalController). `kista.role` 0건 확인은 아래 M10.

### M8 모듈 ID
- R(7): admin(`src/main/java/com/kista/api/admin/package-info.java:14`), benchmark(:4), finance(:2), market(:3), notify(:2), user(:6), web(`api/web/package-info.java:10-12`, CLOSED). T(9): account, broker, marketcalendar, matching, notify(`tradingcore/notify/package-info.java:3`), privacy, stats, trading, web(`tradingcore/web/package-info.java:7-9`, CLOSED). S(3): kernel(`shared/kernel/package-info.java:9-11`), contract(:10-12), platform(:6-8), 모두 OPEN. 합 19 = 지침서 "모듈 19개"와 일치. 동명 충돌 = notify·web 2개(지침서 일치).
- 단언 줄: `src/test/java/com/kista/api/architecture/ModulithArchitectureTest.java:28-29`(R 7+S 3 `containsExactlyInAnyOrder`); `trading-core/src/test/java/com/kista/tradingcore/architecture/ModulithArchitectureTest.java:26-27`(T 9+S 3); `ArchitectureMapTest.java:39-40`(두 모델 그래프), `:43` `contains("api.notify","tradingcore.notify","shared.kernel")`, `:46` `stepModules...contains("tradingcore.trading")`. (13절의 `:40-44`와 대체로 맞음; `:43`이 이름 단언, `:46`이 trading 단언.)
- `ModuleGraphExporter`: 파일 `src/test/java/com/kista/api/architecture/ModuleGraphExporter.java`(이름 규칙 = FQCN 앞 두 세그먼트, `ArchitectureMapExporter.moduleOf` :263-267 주석). 모듈 이름이 FQCN에서 유도되므로 ID 개명만으로는 flows.yml의 `code:`는 안 깨지지만 `moduleViolations`(ArchitectureMapTest:44)가 "api.xxx/tradingcore.xxx" 형식을 가정 — 합친 뒤 모델 1개가 되어도 이름은 `api.*`·`tradingcore.*`로 남음(패키지 불변 단계).
- `ModulithArchitectureTest` R 쪽은 Documenter로 PlantUML을 쓴다(:32~).
- `docs/agents/modules/` 17개: api/{admin,benchmark,finance,market,notify,user,web}.md, shared/{contract,kernel,platform}.md, tradingcore/{account,broker,dca,marketcalendar,matching,notify,privacy,stats,trading-formulas,trading-testing,trading,web}.md (dca·trading-formulas·trading-testing은 모듈 아님). `docs/agents/architecture.md:19,24,43` 모듈 표·ID 설명.
- flows.yml 모듈 이름 형식: 모듈 ID를 직접 쓰지 않는다. `code:`는 단순 클래스명/FQCN(`com.kista.tradingcore` 1줄, `com.kista.api` 3줄뿐). flows.yml 수정 필요량은 M8엔 ~0.

### M9 삭제 대상 (main 참조 파일 / test 참조 파일 / flows.yml / 가드)
| 클래스 (file) | main 참조 | test 참조 | flows.yml `code:` | 가드 |
|---|---|---|---|---|
| T `AppErrorStreamPublisher` (trading-core/.../trading/adapter/out/redis/) | 3: 자기, `notify/adapter/out/gateway/TradingNotifyAdapter`, `web/TradingExceptionReporter` | `AppErrorStreamPublisherTest` | flows.yml:1530 | 없음 |
| R `AppErrorStreamConsumer` (src/.../admin/adapter/in/redis/) | 4: 자기, `admin/adapter/in/event/AppErrorRaisedListener`, `admin/package-info.java`, T Publisher(주석) | `AppErrorStreamConsumerTest` | flows.yml:1540 | `GradleModuleBoundaryTest:90` 주석만 |
| R `UserEventStreamPublisher` (api/user/adapter/out/redis/) | 1(자기) | `UserEventStreamPublisherTest`, `api/web/UserEventResubmitEndToEndIT` | flows.yml:593 | 없음 |
| T `UserEventStreamConsumer` (trading/adapter/in/redis/) | 4: 자기·UserDeletedStreamConsumer·UserNotifyProfileStreamConsumer·Bridge | 0 | 없음 | 없음 |
| T `UserEventRepublisher` | 3: 자기, `TradingUserCascadeListener`(참조), Bridge | `UserCascadeEndToEndIT`, `UserEventRepublisherTransactionTest`, `UserEventStreamBridgeCompatTest`, `UserEventStreamBridgeIT`, `UserEventStreamRecoveryIT` | flows.yml:602 | 없음 |
| T `UserEventStreamBridge` | 5: 자기, SyncListener, DeletedConsumer, Republisher, NotifyProfileConsumer | `UserDeletedStreamConsumerTest`, `UserEventRepublisherTransactionTest`, `UserEventStreamBridgeCompatTest`, `UserEventStreamBridgeIT`, `UserEventStreamRecoveryIT`, `UserNotifyProfileStreamConsumerTest` | 602, 1193 | 없음 |
| T `UserDeletedStreamConsumer` | 3 (자기·Bridge·StreamConsumer) | `UserDeletedStreamConsumerTest`, `UserEventStreamBridgeIT`, `UserEventStreamRecoveryIT` | 601-602 | 없음 |
| T `UserNotifyProfileStreamConsumer` | 3 | `UserNotifyProfileStreamConsumerTest` | (1193 참조는 발행자 줄) | 없음 |
| `UserEventResubmitScheduler` R `src/main/java/com/kista/api/web/UserEventResubmitScheduler.java` (public, `@Component`, `@ConditionalOnProperty(scheduler.enabled)`) / T `trading-core/.../trading/adapter/in/schedule/UserEventResubmitScheduler.java` (package-private, 같은 게이트) | R: 1(자기), T: 자기 +주석 참조 `TradingUserCascadeListener`·`UserNotifyProfileSyncListener`·`UserNotifyProfilePersistenceAdapter`·`UserEventStreamConsumer` | R `UserEventResubmitSchedulerTest`, `UserEventResubmitEndToEndIT`(api/web); T `UserEventResubmitSchedulerTest`, `UserCascadeEndToEndIT` | 없음 | `web_must_stay_pure_inbound_sink`(13절): R은 api.web에 있음 |

본문 diff(R vs T `UserEventResubmitScheduler`): 둘 다 10분 `fixedDelay`, MIN_AGE 5분, BATCH 10_000 동일. **필터 대상 이벤트가 다르다**: R = `UserDeletedEvent`·`UserNotifyProfileChangedEvent`(알림 `NotifyPort.notifyError`); T = 위 둘 + **`AccountDeletedEvent`**(`com.kista.tradingcore.account.application.event.AccountDeletedEvent`, `AccountCascadeListener` 실패분)(알림 `TradingErrorReportPort.reportError`). 메서드명 `resubmitFailedUserEvents` / `resubmitFailedCascadeAndSyncEvents`. **지침서(P5 "남는 한 벌의 필터 대상에 위 두 이벤트가 들어 있는지")에 AccountDeletedEvent 누락 항목 추가 필요** — 합치면 T 쪽 필터(세 이벤트)가 상위집합. Bean 이름 충돌: 둘 다 `userEventResubmitScheduler`.

- `ClockConfig`: R `src/main/java/com/kista/api/ClockConfig.java`(package-private, `@Configuration(proxyBeanMethods=false)`, `Clock.system(TimeZones.KST)`)와 T `trading-core/.../tradingcore/ClockConfig.java` — package 줄만 다르고 **본문 diff 0(동일)**. 가드 참조 `HexagonalArchitectureTest.java:210-211`(`doNotHaveFullyQualifiedName("com.kista.tradingcore.ClockConfig")` 등), 주석 :178. 테스트 `shared/src/testFixtures/.../FixedClockTestConfig.java`가 이름만 언급.
- `PlainDecimalSerializer`: **클래스 없음**(git ls-files 0, main·test 참조 0) — 13절과 일치, M9에서 건너뜀.
- 스트림 키 상수 정의: `shared/src/main/java/com/kista/shared/platform/redis/RedisStreamConfig.java:9` USER_DELETED_STREAM=`stream:user.deleted`, :10 USER_NOTIFY_PROFILE_CHANGED_STREAM=`stream:user.notify-profile.changed`, :11 APP_ERROR_STREAM=`stream:app.error`, :12 PUSH_NOTIFICATION_STREAM=`stream:user.push-notification.requested`. 사용처: USER_DELETED(R Publisher:32, T DeletedConsumer:19), NOTIFY_PROFILE(R Publisher:38, T NotifyProfileConsumer:20), APP_ERROR(R Consumer:33, T Publisher:29), PUSH(T `notify/adapter/out/gateway/RedisPushNotificationPublisher:29`, R `notify/adapter/in/redis/PushNotificationStreamConsumer:32`). 지침서 M9 삭제 후에도 PUSH 2개는 X7까지 남는다. 테스트 참조는 위 표의 테스트들.
- `TradingUserCascadeListener`(trading-core/.../trading/adapter/in/event/, public): `@Component @RequiredArgsConstructor`, `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` :25, `public void onUserDeleted(UserDeletedEvent)` :26, 이벤트 FQCN `com.kista.shared.kernel.UserDeletedEvent`. **`@Transactional` 없음**(주석 :14 "트랜잭션 경계는 UserTradingDataEraseService가 단계마다 연다" — 의도적). 가드 flows.yml:613, :1719.
- `UserNotifyProfileSyncListener`(같은 패키지, package-private): `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` :27 + `@Transactional(propagation = REQUIRES_NEW)` :28, `public void onProfileChanged(com.kista.shared.kernel.UserNotifyProfileChangedEvent)` :29.
- 발행 지점: `UserDeletedEvent` — `src/main/java/com/kista/api/user/application/service/UserCascadeDeleter.java:40`(`deleteCascade` :34, 메서드·클래스 모두 `@Transactional` 없음; 유일 호출자 `UserService.java:199 deleteMe`, UserService는 클래스 `@Transactional` :34 → 트랜잭션 안). `UserNotifyProfileChangedEvent` — `UserNotifyProfilePublisher.java:48`(private publish; 클래스·메서드 `@Transactional` 없음). 호출자: UserService:120(register)·132(approve)·146(reject)·178(reapply) [클래스 @Transactional; login은 NOT_SUPPORTED지만 AdminSeedPromoter:30이 `@Transactional :27`로 감쌈], UserProfileService:32,41,49(클래스 @Transactional :18), UserSettingsService:40,47(메서드 @Transactional :31,:44), AdminSeedPromoter:30. 즉 **전부 호출자 트랜잭션 안에서 발행**; 단 `UserSettingsService.getByUserId`(:25)에는 @Transactional이 없고 resyncNotifyProfile(:45)는 있음. 다른 root 리스너(같은 이벤트): FinanceUserCascadeListener.onUserDeleted:23, UserDeletedNotifier:18, UserFcmCleanupListener:23.

### M10 Caddy·role 가드
- `deploy/server/caddy/kista-api.caddy`(유일한 caddy 파일; 지침서가 말한 "스니펫"). matcher 원문:
  - `@scheduler path_regexp ^/api/admin/scheduler/.+$` → `reverse_proxy kista-scheduler:8080 { import upstream /actuator/health/liveness }`
  - `@trading path_regexp ^/api/(trading-cycles(/.*)?|accounts(/.*)?|backtest|crypto-market-indicators|daily-trades|orders(/.*)?|stats/(summary|equity-curve|cycles)|internal/fida-orders)$` → `kista-trading:8080`
  - 기본 `handle { reverse_proxy kista-api:8080 ... }`. upstream 이름 = `kista-scheduler`, `kista-trading`, `kista-api`(지침서 목표 이름 `kista-worker`/`kista-web`은 아직 없음).
  - `lb_try_duration`: **이 레포에 없음**(파일은 `import upstream <health_uri>` 만 사용; 값은 kista-infra Caddyfile `(upstream)` snippet 소유 — 13절 정정과 일치).
- `CaddyRoutingTest`: `src/test/java/com/kista/api/architecture/CaddyRoutingTest.java`(124줄). 요약: 스니펫에서 `@trading`·`@scheduler`의 path_regexp 값을 읽어(:81-91 loadRoute) 컴파일 산출물(ArchUnit ClassFileImporter, main만)의 모든 `@RestController`/`@Controller` 클래스 `@RequestMapping` × 메서드 경로를 모은다(:96-117). 3개 @Test — (1) `tradingCorePublicPathsAreRoutedToKistaTrading` :41: `trading-core/build/classes/java/main`의 공개 경로(`/api/internal/**` 중 `/api/internal/fida-orders`만 공개)가 @trading에 매치되고 @scheduler엔 안 걸리는지; (2) `rootPathsAreNotCapturedByTradingRoute` :55: `build/classes/java/main`(root)의 경로 중 `/api/admin/scheduler/` 제외 모두 @trading·@scheduler에 안 걸리는지; (3) `schedulerPathsAreRoutedToKistaScheduler` :70: root의 `/api/admin/scheduler/**` 경로가 @scheduler 매치. 단계 M에서 root·trading-core가 한 jar가 되어도 두 classes 디렉토리는 별개 서브프로젝트라 그대로 동작; X1에서 `/api/market/**`·`/api/stats/housing-benchmark/**` 등 엔진 경로 이동 시 이 테스트의 전제(root 경로는 @trading에 안 걸림)가 깨진다 → X1에서 재작성.
- 이미 있는가: `RoleRoutingGuard`·`RoleRoutes`·`kista.role`·`ConditionalOnWorkerRole`·`WRONG_ROLE`·`ENGINE_RESTARTING`·`KISTA_ROLE` — **전부 0건**(git grep 전체, docs/superpowers 제외).
- 서블릿 Filter 빈: `shared/.../platform/security/JwtAuthFilter.java:25`(`extends OncePerRequestFilter`), `InternalTokenAuthFilter.java:23`(둘 다 shared). 둘 다 `SecurityConfig.java`에서 `FilterRegistrationBean`으로 등록 후 비활성: `SecurityConfig.java:86-87`(jwtFilterRegistration), `:94-95`(internalFilterRegistration). root·T에 별도 Filter 클래스 0건.

### M11 내부 HTTP (현황)
- R 내부 HTTP 어댑터 11개(`adapter/out/internal/`), @Override 메서드 합 28:
  | 모듈 | 클래스 | 메서드 | 내부 경로 |
  |---|---|---|---|
  | admin | AccountQueryHttpAdapter | 2 | /api/internal/accounts(:23), /accounts/count(:34) |
  | admin | PortfolioQueryHttpAdapter | 2 | /api/internal/trading/stats/portfolio/{current(:24),history(:32)} |
  | admin | PrivacyQueryHttpAdapter | 6 | /api/internal/privacy/trade-bases(:38), /api/internal/fida-orders(:47), trade-bases/{id}(:64,:76), .../orders(:98), .../orders/{orderId}(:87,:109) |
  | admin | TradingCommandHttpAdapter | 2 | /api/internal/trading/trade-corrections(:28), .../accounts/{accountId}/strategies/{strategyId}/status(:41) |
  | admin | TradingPolicyHttpAdapter | 2 | /api/internal/trading/policy-settings(:32 GET,:53 PUT) |
  | admin | TradingQueryHttpAdapter | 6 | trading/orders(:28), orders/distinct-account-ids(:35), accounts/{id}/strategies(:42), strategies/by-account-ids(:49), strategy-summaries(:57), .../trade-dates(:65) |
  | admin | TradingSchedulerCommandHttpAdapter | 2 | trading/scheduler/open(:22), /close(:30) |
  | benchmark | CurrentExchangeRateHttpAdapter | 1 | trading/stats/exchange-rate(:26) |
  | benchmark | InvestmentPointsHttpAdapter | 1 | trading/stats/investment-points(:29) |
  | market | CandleQueryHttpAdapter | 2 | broker/candles/latest(:34), /range(:48) |
  | market | MarketCalendarQueryHttpAdapter | 2 | marketcalendar/holidays(:22), /session(:33) |
- **X1 "market 2, benchmark 2, admin 7" = 맞음**(어댑터 클래스 수). 포트→소비 서비스: AccountQuery·PrivacyQuery·TradingQuery→AdminQueryService; Portfolio→TelegramBotService; PrivacyQuery→AdminPrivacyTradeService; TradingCommand→AdminStrategyService·AdminTradeCorrectionService; TradingPolicy→AdminSettingsService·RuntimeConfigService·RuntimeSettingsService; TradingSchedulerCommand→AdminTradingSchedulerController(`api/admin/adapter/in/web`); CurrentExchangeRate·InvestmentPoints→BenchmarkService(+StrategyRef); Candle→MarketHolidayService; MarketCalendar→MarketHolidayController·MarketHolidayService.
- T `/api/internal/**` 컨트롤러 13개, 핸들러(@Get/Post/Put/Patch/Delete Mapping) 합 **30**(지침서 31 — 차이 1): AccountInternal 2, CandleInternal 2, MarketCalendarInternal 3, FidaOrderController 1(`/api/internal`+fida-orders), PrivacyBaseInternal 5, PrivacyInternalQuery 1, PortfolioQueryInternal 2, TradingStatsInternal 2, StrategyStatusInternal 1, TradingInternalCommand 1, TradingInternalQuery 6, TradingPolicyInternal 2, TradingSchedulerInternal 2. (T yml:54 `paths-to-exclude: /api/internal/**`). R 어댑터 28 메서드 vs T 핸들러 30 = 차이 2 (FidaOrder 1 외부용 + 1 미사용/다른 용도 추정, 미확인).
- `INTERNAL_API_BASE_URL`: `src/main/resources/application.yml:117`(기본 `http://localhost:8081`); compose `deploy/server/docker-compose.yml:31`(kista-api), `:62`(kista-scheduler) 둘 다 `http://kista-trading:8080`; flows.yml:1466 주석. `InternalApiProperties`(`shared/.../platform/internalapi/InternalApiProperties.java:9`)·`InternalApiClientConfig`(:23,39,45) + `PlatformAutoConfiguration:6,33,37`, `InternalTokenAuthFilter:7,44`; 테스트 `InternalApiClientConfigTest`, `InternalTokenAuthFilterTest`, `RequiredPropertiesValidationTest:3,54`, `application-test.yml:39`; T `application.yml:74`(token만, base-url 없음).
- **R @Scheduled 잡 → 내부 HTTP 어댑터 사슬: 0건.** R `@Scheduled` 11개(KbLandHousingBenchmarkScheduler:24, KbLandPriceIndexScheduler:29·:34, MarketIndexPriceSyncScheduler:26, FinanceRegistrationReminderScheduler:25, FearGreedScheduler:27, RefreshTokenCleanupScheduler:25·:35, web/UserEventResubmitScheduler:37, + Redis 컨슈머 복구 AppErrorStreamConsumer:55·PushNotificationStreamConsumer:54)가 주입받는 서비스(HousingBenchmarkService, MarketIndexPriceSyncService, FinanceRegistrationReminderNotifier, FearGreedService, TokenService, FailedEventPublications+NotifyPort)는 11개 HTTP 포트를 직접·간접 의존하지 않음(포트 소비자 목록에 없음). 내부 HTTP 어댑터를 부르는 것은 전부 요청 경로(컨트롤러·TelegramBotService)뿐 — 따라서 M11의 "worker 자신이 내부 API를 부르는 경로(구 kista-scheduler 잡) → localhost:8080"은 **현재 해당 경로 없음**. 부가: `TradeEventBenchmarkCacheInvalidator:27 @PostConstruct`→`benchmarkService.invalidateUserCache`(:36)는 캐시 무효화만, 어댑터 호출 아님. 스케줄러 role의 HTTP 노출 `AdminSchedulerController`/ `AdminTradingSchedulerController→TradingSchedulerCommandPort→TradingSchedulerCommandHttpAdapter→T TradingSchedulerInternalController` 만 관련(수동 트리거).

### M12 테스트
- `TradingApplication` 단어 참조 테스트: **코드 참조 4개 + 주석 1개** — `src/test/.../architecture/ArchitectureMapTest.java:4`(import, :40 `ApplicationModules.of`), `trading-core/src/test/java/com/kista/shared/platform/scheduling/SchedulerLockServiceTest.java:4,:21`·`SchedulerLockServiceDbTest.java:4,:20`(`@ContextConfiguration(classes = TradingApplication.class)`), `trading-core/.../architecture/ModulithArchitectureTest.java`(:21); 주석만: `src/test/.../admin/AdminInternalApiIntegrationTest.java:35`. 지침서 "6개" = 위 + `TradingApplicationContextLoadTest`(클래스명만, 상향 탐색 암묵 참조) 로 보임 → 단어 grep 기준 5파일(주석 포함 6 아님: ArchitectureMapTest, SchedulerLockServiceTest, SchedulerLockServiceDbTest, T ModulithArchitectureTest, AdminInternalApiIntegrationTest[주석]) + 암묵 1.
- `RootDataJpaTestBase`(`src/test/java/com/kista/api/web/RootDataJpaTestBase.java:8`, 빈 추상 클래스): 상속 테스트 **19개**(`extends RootDataJpaTestBase`), 참조 파일 20개(자기 포함). 지침서 "20개" = 참조 파일 수 기준이고 상속은 19. 루트에서 `extends DataJpaTestBase` 직접은 1개, T는 27개.
- `GradleModuleBoundaryTest` 규칙(위 M1 표). M12가 "R → T 금지 삭제"라 했으나 **그런 규칙 없음**(root main→T 금지를 단언하는 테스트 0건 — 이를 막는 건 `build.gradle.kts:78` 주석의 testImplementation-only 구조뿐). 지울/바꿀 것: `schedulerRoleOnlyPackageMustNotBeReferencedFromOutside`(P4, `:84` AdminSchedulerController 예외), `subprojectClassesStayUnderOwnRootPackage` CSV 8행(`:112-119`; M 이후에도 유효하나 R④ 패키지 루트 이동 시 수정), `tradingCoreTestClassesStayUnderOwnRootPackage`(`:135` `com.kista.shared.platform.scheduling..` 예외 — M12에서 SchedulerLockService 테스트를 shared로 옮기면 삭제 가능), `tradingCoreMustNotCallRootInternalApi`(`:94`, X8까지 유지). 유지: `tradingCoreMustNotDependOnApiOnlyModules`(:19), `tradingCoreTestsMustNotDependOnApiOnlyModules`(:36), `sharedMustNotDependOnTradingCoreOrApiModules`(:55) = "T → R 금지"·"S → R/T 금지".
- `InternalApiContractTest`(`src/test/.../architecture/InternalApiContractTest.java`, 151줄): `internal_api_handlers_must_only_use_contract_types`(:59, 핸들러 수 `isGreaterThan(20)` :79), `contract_must_only_depend_on_allowed_packages`(:85). X8까지 유지; 핸들러 수 하한 20은 X1 중 30→<=20이 되면 실패 → X1에서 같이 조정.
- `ApplicationContextLoadTest`(`src/test/java/com/kista/api/ApplicationContextLoadTest.java`, 71줄, `@SpringBootTest @ActiveProfiles("test")`): 컨텍스트 로드 + `ROOT_JPA_REPOSITORIES = 21`(:68) 일치 + `SecurityFilterChain` 1개 + `ProblemDetailExceptionHandler`·`ExceptionReporter`·`TokenBlacklistPort` 각 1개 + **"trading-core 빈이 컨텍스트에 없다"**(`TRADING_CORE_PACKAGES = com.kista.tradingcore.` :70-71 단언 :60-66) — **M2 직후 이 단언이 반대로 실패한다(M12에서 반드시 삭제·합산)**. 합치면 JPA repo 21+20=41.
- `TradingApplicationContextLoadTest`(`trading-core/src/test/java/com/kista/tradingcore/TradingApplicationContextLoadTest.java`, 42줄): `@SpringBootTest(properties={"spring.data.redis.url=redis://localhost:6379"})`, `TRADING_JPA_REPOSITORIES = 20`(:29), SecurityFilterChain 1, advice/reporter/blacklist 각 1. 지침서 "삭제/참조 교체" 대상 — 합치면 ApplicationContextLoadTest로 통합.
- `SchedulerDisabledContextTest`(`src/test/java/com/kista/api/SchedulerDisabledContextTest.java`, 67줄): `@SpringBootTest(properties="scheduler.enabled=false")`, 스케줄러 빈 9개(주석 :25 "9개 @Scheduled 빈 + AdminSchedulerController")와 `schedule` 패키지 빈 0 단언. `SchedulerRoleConfigTest`(`src/test/java/com/kista/api/SchedulerRoleConfigTest.java`): prod yml `scheduler.enabled=false`(:28) + EPR 재발행 off 파싱 — P4에서 둘 다 `kista.role`로 교체 대상(13절은 SchedulerDisabledContextTest만 언급, **SchedulerRoleConfigTest 누락**).
- testFixtures(`:shared`): `shared/src/testFixtures/java/com/kista/shared/support/{DataJpaTestBase,FixedClockTestConfig,NullMarkedRecordSpecGuard,WebMvcTestSupport}.java` + resources `application-test.yml`. root testFixtures(`DomainFixtures`) `build.gradle.kts:99`, T testFixtures(`TradingFixtures`) `trading-core/build.gradle.kts:76`. root 테스트 → `testImplementation(project(":trading-core"))` `build.gradle.kts:78`; `testImplementation(testFixtures(project(":shared")))` :77.
- `application-test.yml`: **1벌**(shared testFixtures; R·T 테스트가 공유). 키: spring.datasource/modulith/jpa, telegram, crypto.aes-key, jwt.signing-key, kakao, cors, internal.api.token, toss.base-url. main yml은 R `application.yml`+`application-prod.yml`, T `application.yml`+`application-prod.yml`(`application-local.yml`은 gitignore). 합칠 때 R/T 테스트가 같은 yml이라 키 충돌 없음.

### M13 springdoc·스펙 가드
- R yml `src/main/resources/application.yml:78-83` springdoc(api-docs /api-docs, swagger-ui, operations-sorter method) — **`paths-to-exclude` 없음**. T yml `trading-core/src/main/resources/application.yml:52-59` — `paths-to-exclude: /api/internal/**`(:54) + 같은 경로 설정. M13 대로 합친 yml에 `paths-to-exclude` 추가 필요.
- 스펙 가드(S3 추가): `src/test/java/com/kista/api/OpenApiNullMarkedRecordContractTest.java`(`@SpringBootTest RANDOM_PORT`, 받은 스펙은 `build/openapi`에 저장 — 체크인된 스냅샷 파일 없음)와 T `trading-core/src/test/java/com/kista/tradingcore/OpenApiNullMarkedRecordContractTest.java`; 공용 `shared/src/testFixtures/.../NullMarkedRecordSpecGuard.java`(:108 `@Schema(name)` 우선 스키마명 충돌 판정 포함); 변환기 `shared/src/main/java/com/kista/shared/platform/openapi/NullMarkedRecordModelConverter.java`. 이름 충돌 가드: `src/test/java/com/kista/api/architecture/OpenApiSchemaNameCollisionTest.java`(스캔 `com.kista.api`+`com.kista.shared.contract`의 `.adapter.in.web`/`.web.`/`.dto`/contract) + T `trading-core/.../architecture/OpenApiSchemaNameCollisionTest.java`(내용 동일, 패키지만 다름; **합치면 두 패키지를 한 테스트에서 스캔해야 R·T 간 충돌을 잡는다**). 기타 `ErrorCodeOpenApiCustomizerTest`(api/web), `BenchmarkComparisonResponseSchemaTest`, `HousingBenchmarkApiDocsTest`.
- `@Schema(name=` 사용처: shared contract `MarketSessionResponse.java:9`(`InternalMarketSessionResponse`); R `AdminPrivacyBaseResponse:38`, `RuntimeSettingsResponse:56`, benchmark dto 다수(BenchmarkComparisonResponse:20,39,42,52,59,71,80,87; EtfPriceSeriesResponse:13; HousingBenchmarkRegionsResponse:11; HousingBenchmarkSeriesResponse:15; HousingPriceIndexSeriesResponse:15 …); T `BacktestResponse:21,24`, `CryptoMarketIndicatorsResponse:48` 등.
- 지침서 충돌 3건 현재: (1) `Market` — `shared/.../kernel/Market.java` vs `src/main/java/com/kista/api/finance/domain/model/Market.java`, 둘 다 `@Schema(name)` 없음, 둘 다 web DTO 패키지가 아니라 기존 가드 대상 밖(스펙에 enum이 컴포넌트로 노출되는지 실행 필요 — 미검증). (2) `MarketSessionResponse` — `shared/.../contract/marketcalendar/MarketSessionResponse.java`(**이미 `@Schema(name="InternalMarketSessionResponse")` 분리됨**, :9) vs `src/main/.../market/adapter/in/web/dto/MarketSessionResponse.java`(R web DTO, 필드 tradeDateKst·tradingDay) → 충돌 이미 해소(지침서는 미반영). (3) `InvestmentPoint` — R `benchmark/domain/model/InvestmentPoint.java` vs T `stats/domain/model/InvestmentPoint.java`(둘 다 domain, `@Schema` 없음; 와이어는 contract `InvestmentPointsResponse`) → 스펙에 직접 안 실리면 충돌 아님(미검증).

### M14 리소스
- Hikari: R `src/main/resources/application.yml:14-19` max 10 / min-idle 2 / pool-name `HikariPool-KistaApi` / `connection-init-sql: SET search_path TO finance, kista_ref, public`(:19). T `trading-core/src/main/resources/application.yml:14-19` max 10 / min-idle 2 / `HikariPool-KistaTrading` / `SET search_path TO trading, trading_ref`(:19). prod: R `application-prod.yml:3-8` max **5**, min-idle 1, connection-timeout 20000, idle 300000, max-lifetime 1800000; T `application-prod.yml:3-8` 동일 값. (지침서 "지금 JVM당 5" 일치 — prod 기준.)
- `spring.task.scheduling.*`: R `application.yml:48-51` = `thread-name-prefix: kista-api-scheduler-` 만(`pool.size` 없음 → 기본 1). T yml에는 `task.scheduling` **없음**. `TaskScheduler` 직접 주입: `trading-core/.../trading/adapter/in/schedule/TradingCloseScheduler.java:51`(마감 1회 예약에 `taskScheduler` 사용) — 합치면 R `@Scheduled` 8개 + T `@Scheduled`가 같은 풀을 쓰고 마감 예약 `taskScheduler.schedule`도 같은 풀 → M14 풀 크기 항목 실효. 사용자 정의 TaskScheduler/SchedulingConfigurer 빈은 0건(Boot 자동구성만).
- compose `deploy/server/docker-compose.yml`(3 서비스; `JAVA_TOOL_OPTIONS`는 **쓰지 않는다** — 런타임은 `JAVA_OPTS`(Dockerfile ENTRYPOINT가 `$JAVA_OPTS`), `JAVA_TOOL_OPTIONS`는 Dockerfile 빌드 단계 `-Xmx768m`만 :8):
  | 서비스 | image 변수 | container_name | JAVA_OPTS 힙 | mem_limit | stop_grace_period | environment 키 | 네트워크 |
  |---|---|---|---|---|---|---|---|
  | kista-api (:28-) | `KISTA_API_IMAGE` | 없음(blue/green) | `-Xmx1280m -Xms256m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=96m` (`${JAVA_OPTS:-...}`) | 2048m | 35s | SPRING_PROFILES_ACTIVE, SCHEDULER_ENABLED("false"), REDIS_URL, INTERNAL_API_BASE_URL, JAVA_OPTS | shared_net, data_net |
  | kista-scheduler | `KISTA_SCHEDULER_IMAGE` | kista-scheduler | `-Xmx896m -Xms128m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m` | 1536m | 200s | SPRING_PROFILES_ACTIVE, SCHEDULER_ENABLED("true"), SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART("true"), REDIS_URL, INTERNAL_API_BASE_URL, JAVA_OPTS | 동일 |
  | kista-trading | `KISTA_TRADING_IMAGE` | kista-trading | `-Xmx2048m -Xms256m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=80m` | 2816m | 200s | APP_JAR(trading-core.jar), SERVER_PORT("8080"), SPRING_PROFILES_ACTIVE, SCHEDULER_ENABLED("true"), SPRING_MODULITH_..._ON_RESTART("true"), REDIS_URL, JAVA_OPTS | 동일 |
  공통 env_file `.env`, healthcheck `readiness` 30s/10s/start 180s/retries 3. mem_limit 합 6400m(주석 :88-93). 지침서 "web 1280m·worker 2048m 시작"은 현재 api 1280m / trading 2048m 과 일치.
- `.env.example` 키(24줄 중 키 24개, 51줄): SPRING_PROFILES_ACTIVE, DB_URL, DB_USERNAME, DB_PASSWORD, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, AES_ENCRYPTION_KEY, JWT_SIGNING_KEY, KAKAO_CLIENT_ID, KAKAO_CLIENT_SECRET, CORS_ALLOWED_ORIGINS, ADMIN_KAKAO_IDS, INTERNAL_API_TOKEN, FIREBASE_SERVICE_ACCOUNT_JSON, ALPACA_API_KEY, ALPACA_API_SECRET, TOSS_ADMIN_CLIENT_ID, TOSS_ADMIN_CLIENT_SECRET, HEARTBEAT_OPEN_URL, HEARTBEAT_CLOSE_URL, HEARTBEAT_CRYPTO_URL, GRAFANA_CLOUD_OTLP_ENABLED, GRAFANA_CLOUD_OTLP_ENDPOINT, GRAFANA_CLOUD_OTLP_AUTH_HEADER. (`SCHEDULER_ENABLED`·`REDIS_URL`·`INTERNAL_API_BASE_URL`는 .env.example에 없고 compose environment에서 주입.) `FIREBASE_SERVICE_ACCOUNT_JSON`이 worker에도 필요해지는 건 X7(지침서).

### 배포 파일 (이 레포 쪽)
- `find deploy -type f` (7개): `deploy/grafana/kista-api-dashboard.json`, `deploy/hooks/epr-orphans.sh`, `deploy/hooks/pre-apply.sh`, `deploy/server/README.md`, `deploy/server/bluegreen`, `deploy/server/caddy/kista-api.caddy`, `deploy/server/docker-compose.yml`, `deploy/server/roles` (= 8개 파일). **K-3 표 대비 사라진 파일**: `deploy/server/readiness`(`compose-invariants.bats:27` "readiness 계약 파일 폐지"), `deploy/server/schema-reorg/RUNBOOK.md`(09 T2 삭제). K-3에 있고 존재하는 나머지: `infra/prometheus/prometheus.yml`, `scripts/dev-seed/seed.sh`(:14 `TRADING=${TRADING:-http://localhost:8081}` 그대로), `.github/CLAUDE.md`, grafana 대시보드(`application="kista-api"` 17곳 확인). 빠진 파일 없음(새로 생긴 deploy 파일 없음).
- `.github/scripts/`(4): `check-doc-budget.sh`(항상 로드 문서·모듈 문서 바이트 예산 검사), `check-migrations.sh`(Flyway 이력 불변 + root expand/contract 검사; `ALL_DIRS` 두 디렉토리 :17 / root만 expand-contract), `detect-deploy-scope.sh`(변경 파일→verify/config/api/scheduler/trading 플래그), `plan-deploy.sh`(infra state와 diff로 role별 target SHA·build·deploy 계획).
- `.github/tests/*.bats`(6): check-migrations, compose-invariants, deploy-plan, doc-budget, epr-orphans, pre-apply. role 이름 하드코딩 줄: `compose-invariants.bats:2,8,13,14,16,20,21,22,23(`roles` = "kista-trading kista-api kista-scheduler "),29,31-34(caddy 파일명 kista-api.caddy),35,39,40,41`; `deploy-plan.bats:19,36(caddy 파일),39,46,53,65,76,84`(state_of :39가 3 role 형식, `kista-trading=$C3 kista-api=$C1 kista-scheduler=$C1` :46); `pre-apply.bats:17,18,24,31,37`. `check-migrations.bats`·`epr-orphans.bats`·`doc-budget.bats`는 role 이름 없음.
- `.github/workflows/*.yml`의 role 이름: **없음**. `server-deploy.yml`엔 `kista-api`가 앱 식별자로만 나온다: `:27`(주석), `:59`(`repos/narafu/kista-infra/contents/state/kista-api.yml`), `:207`(`client_payload[app]=kista-api`; roles는 `:37,:187 ROLES`가 plan 출력을 그대로 전달). `ci.yml`엔 role 없음. → 워크플로 수정 불필요(K-3과 일치).
- `deploy/hooks/pre-apply.sh:11-15` role→schema 매핑 원문: `case "$role" in kista-trading) schema=trading ;; kista-scheduler) schema=public ;; *) continue ;; esac`(:12-16) — kista-api는 건너뛴다. `epr-orphans.sh`는 FQCN 기반이라 role·스키마 무관(`source_of`는 `src/main/java/` 경로로 서브프로젝트 무관 조회, :16-19).
- `detect-deploy-scope.sh` 규칙(:20-48): 테스트 경로→verify; `trading-core/src/main/*`→verify+trading; `src/main/resources/db/migration/*`→verify+api+scheduler; `src/main/java/*/adapter/in/schedule/*`·`AdminSchedulerController.java`→verify+scheduler; 그 외 `src/main/*`→verify+api+scheduler; `deploy/server/caddy/*`→verify+config; compose·roles·bluegreen·`deploy/hooks/*`→config; `shared/src/main/*`·빌드 파일(`build.gradle.kts`, `trading-core/build.gradle.kts`, settings, gradle/*, Dockerfile …)→verify+api+scheduler+trading. 출력 플래그 5개(verify, config, api, scheduler, trading).
- `plan-deploy.sh`: state에서 `config:`와 `  <role>: <sha>` 쌍을 읽고(:17-18), role별로 `pick "$sha" "${role#kista-}"`(:46 — role 이름에서 `kista-` 접두를 떼 플래그 키로 사용)로 target 또는 기존 SHA 결정. **role을 `kista-web`/`kista-worker`로 바꾸면 키 `web`/`worker`가 필요해 `detect-deploy-scope.sh`가 그 플래그를 내야 한다.**
- `docs/agents/docker-infra.md` "배포 직전 EPR 정리 런북"(:121-137) 요약: ① 자동 검사 — kista-infra Reconcile이 `pre-apply.sh` 실행, 이미지가 바뀌는 role이 kista-trading이면 `trading.event_publication`, kista-scheduler면 `public.event_publication` 미완료 row의 이벤트·리스너 클래스·메서드가 배포 대상 커밋 소스에 있는지 확인(`EPR_REF`), 없으면 적용 차단·조회 실패도 차단(fail-closed), 긴급 우회는 workflow_dispatch force. ② 이벤트·리스너 패키지 이동·개명 커밋은 이미지 교체 직전에 옛 이름 미완료 row를 확인·삭제(유실 감수, `completion_date` 위장 금지). ③ SQL: `SELECT count(*) ... event_type LIKE '<옛 패키지>.%'` → 0 아니면 `DELETE`. ④ 리스너만 이동(이벤트 FQCN·시그니처 불변, 멱등)이면 `listener_id`(`FQCN.메서드(이벤트FQCN)`)를 `UPDATE ... SET listener_id`로 갱신해 재시도 유지; `public.event_publication`은 kista-api가 로드한 리스너 id로 기록되고 kista-scheduler가 재발행하므로 두 role 교체 시점 주의. **"패키지 루트 분리 배포"(:151-154)는 3줄 스텁**: 2026-10 루트 분리는 모든 미완료 row가 고아가 되므로 위 런북 그대로 적용 — 10줄 요약할 분량 없음.

### 단계 X·R 가벼운 확인
- X1: R 내부 HTTP 어댑터 = market 2(CandleQuery, MarketCalendarQuery)·benchmark 2(CurrentExchangeRate, InvestmentPoints)·admin 7 = 11 → 지침서와 일치. 메서드 합 28(admin 22, benchmark 2, market 4). T 내부 핸들러 30(지침서 31, 차이 1).
- X6 `TradingUserProfilePort` 호출처(main, 필드 file:line, trading-core/src/main/java/com/kista/tradingcore/ 기준): notify/adapter/out/gateway/ApiKeyExpiryNotifier:16, CryptoTradeNotifier:24, CycleEndedNotifier:21, CycleLifecycleNotifier:21, TradingAlertNotifier:34, TradingReportNotifier:30, TradingUserProfiles:18(헬퍼); trading/adapter/in/event/UserNotifyProfileSyncListener:25; trading/adapter/in/schedule/BatchContextFactory:31; trading/adapter/out/persistence/UserNotifyProfilePersistenceAdapter:30(구현체); trading/application/service/CryptoDcaRunner:42, MarketEventNotifier:27, StrategyCreationService:62, UserTradingDataEraseService:35; 포트 정의 trading/application/port/output/TradingUserProfilePort:11. 소비 notifier 6종(지침서 7종), `CryptoTradeNotifier`·`CryptoDcaRunner`는 아직 존재(13절은 15번 지침서가 지운다고 함 — 미실행), `TradingUserProfiles`·`UserNotifyProfilePersistenceAdapter`·`UserNotifyProfileSyncListener`는 13절대로 목록 추가 대상. 테스트 참조 15파일(mock). 채널 판정 2곳: `TradingUserNotificationAdapter.java:175`는 `sendIfLinked`(채널 판정 아님, 줄 이동), `UserPushNotificationAdapter.java:32` `notificationChannel().includesFcm()` — 지침서 줄 번호(:175) 낡음.
- X7 스트림: `user.notify-profile.changed` 발행 R `api/user/adapter/out/redis/UserEventStreamPublisher.java:37-38`, 컨슈머 T `trading/adapter/in/redis/UserNotifyProfileStreamConsumer.java:20`, 리스너 `UserNotifyProfileSyncListener:27-29`. `user.push-notification.requested` 발행 T `notify/adapter/out/gateway/RedisPushNotificationPublisher.java:29`, 컨슈머 R `notify/adapter/in/redis/PushNotificationStreamConsumer.java:32`(+복구 @Scheduled :54, @EventListener ApplicationReady :39).
- X8: `com.kista.shared.contract` 타입 19개(account1, broker1, marketcalendar1, notify2[TradeEventEnvelope·TradeEventMessage], privacy6, stats3, trading5), FIDA 타입 = `privacy/FidaOrderRequest`, `privacy/FidaOrderResponse`(+nested OrderItem). `InternalApiClientConfig`·`InternalApiProperties`·`INTERNAL_API_BASE_URL` 참조처는 M11 절.
- `shared.kernel` 타입 42개(java, package-info 제외; Strategy* 9, Dca* 7, Order* 3, User* 5 포함). 이벤트 4: UserDeletedEvent, UserNotifyProfileChangedEvent, AppErrorRaisedEvent, UserPushNotificationRequestedEvent.
- 13절 가드 줄 번호 현재: `GradleModuleBoundaryTest:55`(shared 규칙 @Test 메서드 sharedMust…, 맞음), `:120`(subprojectClassesStay… 메서드, 맞음), `:130`(T 테스트 루트 규칙, 맞음), CSV `:112-119`·메서드 `:112-130` 맞음. `ArchitectureMapTest:31-44` 맞음(모델 :39-40, 단언 :43, :46). **`HexagonalArchitectureTest`는 밀림(565줄)**: 13절 `:70,99,136` → 벤더 패키지 상수 `:61-63`·`:70`(infra 패키지 목록), kernel 규칙 `:105-108`, contract `:120-124`, platform `:135-138`; `:296-311`(web/앱셸·Redis 구독자 규칙) → 구독자 `:298-304`, @Aspect `:306-312`, 앱셸 `:285-289`; `:179-180`(ClockConfig FQCN) → **`:210-211`**(주석 :177 "앱 루트 ClockConfig 2곳만 예외"); `:112-130` 같은 파일 참조는 GradleModuleBoundaryTest 쪽. 그 외 `com.kista.tradingcore` 문자열: :61-63,78-80,153-159,234-238,272,285,329-357. `importPackages("com.kista")` 전체 스캔 :84-85. `TradingCoreTestVendorModelTest`(trading-core/.../architecture/) 패키지 문자열: :23(`com.kista`), :31-32(trading/web/stats/account/privacy/marketcalendar/matching…), :34(broker vendor model 5개).
- EPR 대상 리스너(@TransactionalEventListener 메서드, 주석 제외): R 16개(+@EventListener 2: AppErrorRaisedListener, FinanceReminderChannelNotifier), T 20개(+@EventListener 6: MarketCalendarRefreshScheduler, TradingStatsService×2, UserEventStreamConsumer, TradingBatchResumer, AppErrorStreamPublisher), shared 0. @ApplicationModuleListener 0건. 이벤트 클래스(`*Event.java` 34개) 분포: shared.kernel 4, shared.platform.scheduling 1(SchedulerLifecycleEvent), R `api.{benchmark,finance,market}.application.event` 각 1, `api.user.application.event` 6, T `tradingcore.account.application.event` 2, `privacy.application.event` 1, `trading.application.event` 16, `trading.domain.model` 1.
- `src/test/resources/architecture/single-impl-interfaces.txt`: 유효 줄 82개; `PENDING_REMOVAL_CEILING = 82`(`HexagonalArchitectureTest.java:74`, 단언 :441-442) → 동수(여유 0). `DEPENDENCY_INVERSION_PORTS` 4개(:76-80).
- 배포 절 미완 항목: 모두 위에서 채움. (kista-infra 값인 `lb_try_duration`·`build-bundle.sh:17`은 이 레포에서 확인 불가.)

# 부록 D. kista-infra 배포 구성 원본

### 1. 레포 트리와 배포 흐름 요약

infra 파일(비 .git, 비 .idea): `.sops.yaml`, `CLAUDE.md`(10줄, README가 SSOT), `README.md`(125줄), `Caddyfile`, `docker-compose.yml`, `deploy/bin/{apply-infra,backup,drift-check,lib,reconcile}.sh`, `.github/workflows/{reconcile,server-deploy}.yml`, `.github/actions/ssh-setup/action.yml`, `.github/scripts/{build-bundle,resolve-request,state-lib,trading-guard}.sh`, `.github/tests/{compose-scale,deploy-bin,drift,reconcile,request,trading-guard}.bats` + `stub/`·`stub-reconcile/`, `state/{kista-api,kista-ui}.yml`, `secrets/{README.md,infra.env,kista-api.env,kista-ui.env}`(값 안 읽음), `docs/runbooks/postgres-major-upgrade.md`, `docs/superpowers/{fable-review,specs}`.

흐름(README.md "배포"):
- infra(`server-deploy.yml`): main push 또는 dispatch. infra compose·.env가 서버 적용본과 다를 때만 KST 월~금 22:20~23:40 / 화~토 04:20~06:20 차단(`trading-guard.sh:13-14`). `force=true`로 우회.
- 앱(`reconcile.yml`): 앱 CI의 `repository_dispatch(deploy, payload.app)` -> 검증 -> 서버 `reconcile.sh` -> 성공 시 `state` 커밋. 앱 추가 = `state/<app>.yml` + `secrets/<app>.env`.
- README.md "main push가 곧 무승인 배포다"(infra 자체도 동일).
- 앱 reconcile에는 매매 시간대 가드가 없다(trading-guard는 infra compose/.env 변경 때만, `server-deploy.yml:103-114`).

### 2. roles 정의 (api 번들 안: `api:deploy/server/roles`, `bluegreen`)

```
# reconcile 적용 순서 — trading 먼저(kista-api가 trading 내부 API 호출), kista-api가 scheduler보다 먼저(user_notify_profile 발행)
kista-trading
kista-api
kista-scheduler
```
`bluegreen`: `kista-api` 한 줄(주석에 "kista-trading·kista-scheduler 추가 금지 — compose-invariants.bats가 잠금").

| role | 적용 순서 | 교체 방식 | readiness | stop_grace_period |
|---|---|---|---|---|
| kista-trading | 1 | stop-first (`reconcile.sh:141-148` `up -d --no-deps`), `container_name: kista-trading` | 공용 healthcheck, Docker `healthy`만 판정(`reconcile.sh:88-102`) | 200s (`docker-compose.yml:105`) |
| kista-api | 2 | blue/green(`reconcile.sh:133-139` -> `bg_swap` `:113-128`), container_name 없음 | 동일 | 35s (`:38`) |
| kista-scheduler | 3 | stop-first, `container_name: kista-scheduler` | 동일 | 200s (`:70`) |

- readiness 계약 파일은 없다. compose 공용 앵커 `x-healthcheck`가 `wget .../actuator/health/readiness`, interval 30s, timeout 10s, start_period 180s, retries 3(`api:deploy/server/docker-compose.yml:8-13`).
- 게이트: `GATE_ATTEMPTS=30` x `GATE_INTERVAL=10s` = 최대 5분, `unhealthy`면 즉시 중단(`reconcile.sh:18-19,88-102`).

### 3. state

`infra:state/kista-api.yml` 전문(2026-11-15 기준):
```
# 마지막으로 서버 적용에 성공한 상태 — reconcile.yml만 쓴다
config: 8af24c94b20e8370b76e0b8837a7de549593137a
roles:
  kista-trading: e044e983c60bdb344bfeae788f12c475943853c8
  kista-api: e044e983c60bdb344bfeae788f12c475943853c8
  kista-scheduler: e044e983c60bdb344bfeae788f12c475943853c8
```
`state/kista-ui.yml`: `config: 6c3698c8...`, `roles: kista-ui: 6c3698c8...`.

- 형식은 `state-lib.sh:2-9`(grep/sed 파서): `config: <sha40>`, `roles:` 아래 `  <role>: <sha>`. role 정규식 `^  [a-z][a-z-]*: [0-9a-f]*$`.
- `config` = 앱 레포에서 compose·roles·Caddy 스니펫을 읽는 커밋. `roles.<role>` = 그 role 이미지(`ghcr.io/narafu/<app>:<sha>`)의 커밋. 현재 3 role이 같은 SHA(같은 이미지 1개 재사용)이고 config만 다른 커밋.
- 커밋 주체: `reconcile.yml:167-178`, `github-actions[bot]`가 서버 적용 성공 뒤 `git commit -m "deploy(${APP}): ${ID}"`, id = `<config12>-<images.env sha256 8자>`(`build-bundle.sh:28`). 변경 없음(changed=false)이면 커밋 없음.
- state는 단조 증가: `resolve-request.sh:39-43` `newer()`가 "요청이 state보다 새것"일 때만 요청을 채택, state가 요청의 자손이거나 같으면 state 유지. SHA는 앱 `origin/main`의 조상이어야 함(`:35`).

### 4. build-bundle.sh (`infra:.github/scripts/build-bundle.sh`)

- 입력 env `APP_DIR`·`STATE`·`IMAGE_REPO`·`OUT`. 번들 = `deploy/server/docker-compose.yml` + `roles`·`bluegreen`(CRLF 제거, `:13`) + `caddy/` 디렉토리 + `images.env`(role별 `KISTA_<ROLE>_IMAGE=ghcr.io/narafu/kista-api:<sha>`, `image_var`은 `KISTA_API_IMAGE` 식, `state-lib.sh:22`).
- 지침서가 말한 "roles와 state 키 집합이 다르면 실패"는 **`build-bundle.sh:17-20`**:
```
17: if ! cmp -s <(grep -vE '^[[:space:]]*(#|$)' "$OUT/roles" | sort) <(state_roles "$STATE" | cut -d= -f1 | sort); then
18:   echo "::error::deploy/server/roles와 state의 role 목록이 다름" >&2
19:   exit 1
```
  - 지침서 05 4-3의 `build-bundle.sh:17`은 맞다. 이 검사는 번들 구성 단계이므로 서버를 건드리기 전에 실패한다(reconcile.yml:114 bundle 단계가 upload·hook·reconcile보다 앞).
- 출력 `id=<config12>-<hash8>`가 `/opt/kista-api/releases/<id>/`로 업로드(`reconcile.yml:145-155`, 이미 있으면 건너뜀).

### 5. reconcile 사슬

진입점: api `server-deploy.yml` deploy 잡 `gh api repos/narafu/kista-infra/dispatches event_type=deploy` (`api:server-deploy.yml:206-208`, payload app/config/roles/sha/force) -> infra `reconcile.yml`:
1. checkout main(`:57-59`) -> 앱 검증(`:62-65`, `state/<app>.yml` 존재) -> status pending(`:85-91`) -> 앱 레포 checkout(`:93-97`)
2. `resolve-request.sh`(`:99-108`): SHA 형식·조상 검증, state 병합, 이미지 존재 확인(`require_image`, 재시도 5회·5초 배수), changed/hook_roles 출력. 요청 role이 state에 없으면 `::error::state에 없는 role`(`resolve-request.sh:65-67`).
3. `build-bundle.sh`(`:114-125`) -> ssh -> `pre-apply hook`(`:137-142`, FORCE=true면 생략, `app/deploy/hooks/pre-apply.sh`) -> 번들 업로드 -> `nohup bash /opt/kista-infra/bin/reconcile.sh <app> <id>`(`:157-164`) -> state 커밋(`:167-178`) -> 실패 시 텔레그램 `[배포 실패] <app> reconcile`(`:180-188`) -> commit status(`:191-199`).
4. `deploy/bin/reconcile.sh <app> <release|current>`:
   - `host_lock -x`로 호스트 전역 직렬화(`:22`, lock `/opt/kista-infra/.reconcile.lock`, `lib.sh:6-9`).
   - role 실행 명령: `docker compose -p <app> --project-directory /opt/<app> -f <rel>/docker-compose.yml --env-file /opt/<app>/.env --env-file <rel>/images.env <...>`(`lib.sh:12-16`). pull은 `compose pull "${roles[@]}"`(`:54`), 기동은 role별 `up -d --no-deps <role>`(`:142`). 즉 **프로젝트명 = 앱 이름(`kista-api`)**, role = compose 서비스명.
   - **`--remove-orphans`는 어디에도 없다**(두 레포 전체 grep 0건). roles에 없는 서비스의 컨테이너는 지우지 않고 계속 돈다(compose가 orphan 경고만).
   - blue/green(`bg_swap` `:113-128`): 사전 검사에서 role 컨테이너가 정지 포함 2개 이상이면 exit 2(`:45-49`). 실행 중 컨테이너가 있으면 `up --dry-run`으로 재생성 필요 판정(`:117-119`, 변경 없으면 skip) -> `up -d --no-deps --scale role=2 --no-recreate role`로 새 컨테이너를 옆에 띄움(`:122`) -> `gate`(`:127`). Caddy upstream은 **서비스 DNS 이름(`kista-api:8080`)이라 컨테이너 전환 시 별도 갱신이 없다**(두 컨테이너가 같은 서비스 alias로 DNS 라운드로빈, Caddy `health_uri`가 죽은 쪽을 뺌). 옛 컨테이너는 **모든 role + Caddy 스니펫 reload 통과 뒤** `docker stop`(StopTimeout=stop_grace_period) + `docker rm`(`:173-180`).
   - 실실행 중 컨테이너가 없는 blue/green role(첫 배포)은 stop-first 경로로 뜬다(`:136-140` 조건 `[ -n "$running" ]` 불성립 -> `:141-148`).
   - Caddy 스니펫: 모든 role 통과 후 `$rel/caddy/<app>.caddy`를 `/opt/kista-infra/routes/<app>.caddy`에 임시파일+mv로 설치하고 `docker exec <caddy> caddy reload`(`:151-171`). reload 실패 시 스니펫 원복 후 `rollback`. 스니펫 내용이 같으면 reload 생략.
   - 마무리: `current`/`previous` 심링크 전환, release KEEP_RELEASES=5개 유지, 미사용 이미지 태그 정리(`:182-199`).
   - 자동 롤백: 있음. 이번에 바꾼 role을 역순으로(`:60`). blue/green role은 새 컨테이너 rm + 옛 컨테이너 헬스 재확인(`:63-71`), 나머지는 **`compose "$prev" up -d --no-deps "$role"`로 직전 release의 compose로 되돌림**(`:76`). 롤백 불가·실패는 exit 2.
   - exit: 0 성공 / 1 실패(롤백 뒤 헬스 통과 또는 미변경) / 2 수동 복구(`:5`).
5. 고아 감지(03 T8, 구현 완료 `533cc22`): `drift-check.sh:35-43`. cron 15분. 앱 project의 **실행 중** 서비스(`docker ps --filter label=com.docker.compose.project=<app>`, oneoff=False) 중 `roles`에 없는 것을 `"<app>: 고아 <서비스>"`로 **텔레그램 알림만**(자동 삭제 없음). 정지된 컨테이너는 알리지 않음(롤백용 정지 허용, `:36`). 같은 보고는 `.drift-last`로 1회만 알림(`:65-70`), reconcile 중이면 `host_lock -n`으로 건너뜀(`:24`). 알림 토큰은 `/opt/kista-api/.env`의 `TELEGRAM_BOT_TOKEN`/`CHAT_ID`(`:12-14`). drift-check는 blue/green role이 2개 이상 잔존하는 경우도 알린다(`:57-62`).
6. `.env` 변경은 `apply-infra.sh:35-44`가 `reconcile.sh <app> current`로 재적용(`current` 없으면 경고 후 건너뜀).

### 6. compose

#### infra `docker-compose.yml` (프로젝트명 `kista-infra`, `:6`)
| 서비스 | 이미지 | 포트 | 네트워크 | mem_limit |
|---|---|---|---|---|
| caddy | caddy:2.9-alpine | 80:80, 443:443 | shared_net | 64m |
| postgres (`container_name: kista-postgres`, alias `postgres`) | postgres:18.6 | 127.0.0.1:5432:5432 | data_net | 3072m |
| redis (alias `redis`) | redis:7-alpine (`--appendonly yes --appendfsync everysec`) | 없음 | data_net | 256m |
볼륨 caddy_data·caddy_config·postgres18_data·redis_data. 네트워크 shared_net·data_net은 external(`ensure_networks`가 `docker network create`, `lib.sh:29-32`). caddy 마운트: `./Caddyfile`, `./routes:/etc/caddy/routes:ro`(`:19-22`). caddy env: `API_DOMAIN`, `UI_DOMAIN`.

#### api `deploy/server/docker-compose.yml` (줄은 api 파일)
공통: `env_file: .env`, 네트워크 shared_net + data_net, `expose: 8080`(포트 공개 없음), `restart: unless-stopped`, healthcheck 앵커(위 2절), logging json-file 50m x 5.

| 항목 | kista-api | kista-scheduler | kista-trading |
|---|---|---|---|
| 줄 | 22-43 | 48-75 | 84-112 |
| image 변수 | `${KISTA_API_IMAGE:?}` | `${KISTA_SCHEDULER_IMAGE:?}` | `${KISTA_TRADING_IMAGE:?}` |
| container_name | 없음(blue/green이라 `kista-api-kista-api-N`) | `kista-scheduler` | `kista-trading` |
| `SPRING_PROFILES_ACTIVE` | prod | prod | prod |
| `SCHEDULER_ENABLED` | `"false"` | `"true"` | `"true"` |
| `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART` | 없음(application-prod.yml 기본 false, `api:src/main/resources/application-prod.yml:19`) | `"true"` (`:60`) | `"true"` (`:96`) |
| `INTERNAL_API_BASE_URL` | `http://kista-trading:8080` (`:31`) | `http://kista-trading:8080` (`:62`) | 없음(root를 부르지 않음) |
| 기타 env | `REDIS_URL=redis://redis:6379`, `JAVA_OPTS` | 동일 + `JAVA_OPTS` | `APP_JAR=trading-core.jar`, `SERVER_PORT="8080"`, `REDIS_URL`, `JAVA_OPTS` |
| `JAVA_OPTS` | `-Xmx1280m -Xms256m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=96m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom` (`${JAVA_OPTS:-...}` 기본값, `.env`로 덮을 수 있음) | `-Xmx896m -Xms128m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m ...` | `-Xmx2048m -Xms256m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=80m ...` |
| stop_grace_period | 35s | 200s | 200s |
| mem_limit | 2048m | 1536m | 2816m |

- **`SPRING_FLYWAY_*`와 `JAVA_TOOL_OPTIONS`는 compose에 없다**(grep 0건). `JAVA_TOOL_OPTIONS`는 `api:Dockerfile:6`이 빌드 스테이지에서만 `-Xmx768m`로 설정. Flyway 프로퍼티는 yml에서 처리(compose가 아님).
- 이미지: **빌드는 1개**(`Dockerfile`이 `app.jar`과 `trading-core.jar`을 한 이미지에 넣음, `api:Dockerfile:37-38`, ENTRYPOINT `java $JAVA_OPTS -jar ${APP_JAR:-app.jar}`(`:56`)). kista-trading만 `APP_JAR=trading-core.jar`로 같은 이미지를 재사용. 이미지 태그는 `ghcr.io/narafu/kista-api:<GITHUB_SHA>` 단 하나(`api:server-deploy.yml:150`).
- 호스트 mem 합계 주석(`:106-109`): 앱 2048+1536+2816 + infra 3392 + ui 1536 = 11328m, 호스트 11.6GiB 이하(2 role 전환 시 합계 재계산 필요).
- 새 컨테이너 이름: blue/green 교체 중 새 컨테이너는 `kista-api-kista-api-N`(`:24`).
- `KISTA_ROLE` env는 아직 compose에 없다(05 지침서가 추가 예정).

### 7. Caddy

`infra:Caddyfile` 전문 요약: `(upstream)` 스니펫(`:3-14`) = `transport http { keepalive off }`, `lb_try_duration 120s`, `lb_try_interval 500ms`, `health_uri {args[0]}`, `health_interval 10s`, `health_timeout 5s`. 끝줄 `import /etc/caddy/routes/*.caddy`(`:21`).

`api:deploy/server/caddy/kista-api.caddy` 전문(29줄) 핵심:
```
{$API_DOMAIN} {
	encode zstd gzip
	@scheduler path_regexp ^/api/admin/scheduler/.+$
	handle @scheduler { reverse_proxy kista-scheduler:8080 { import upstream /actuator/health/liveness } }
	@trading path_regexp ^/api/(trading-cycles(/.*)?|accounts(/.*)?|backtest|crypto-market-indicators|daily-trades|orders(/.*)?|stats/(summary|equity-curve|cycles)|internal/fida-orders)$
	handle @trading   { reverse_proxy kista-trading:8080   { import upstream /actuator/health/liveness } }
	handle            { reverse_proxy kista-api:8080       { import upstream /actuator/health/liveness } }
}
```
(`@trading` 줄 `:18`, 위 정규식 원문은 파일 참조. `import upstream` 3회.)
- `lb_try_duration` 120s는 infra Caddyfile `:9`의 값(지침서 05 13절의 "kista-infra에서 지금 값을 읽는다" 요청의 답). 03 5-5가 권장하는 엔진 대역 5초는 아직 반영 안 됨.
- 설치·reload: `reconcile.sh:151-171`(앱 스니펫만, 사이트 주소 `{$API_DOMAIN}`는 caddy 컨테이너 env). infra Caddyfile만 바뀌는 경우는 `apply-infra.sh:31`의 `caddy reload`. **reload 실패는 재생성 폴백 없이 실패**(`cfb3a5a`).
- ui 라우팅은 `kista-ui` 스니펫(별도 앱 소유).

### 8. hooks (`api:deploy/hooks/`)

- `pre-apply.sh`: `HOOK_ROLES`("role=sha", 이번에 이미지가 바뀌는 role)를 돌며 **role->schema 매핑 `kista-trading -> trading`, `kista-scheduler -> public`, 그 외는 `continue`**(`:12-16`). `docker exec kista-postgres psql ... SELECT DISTINCT event_type, listener_id FROM <schema>.event_publication WHERE completion_date IS NULL`(`:17`) 후 `epr-orphans.sh`에 `EPR_REF=<role sha>`로 stdin 전달. **미완료 행이 이벤트·리스너 클래스·메서드 소스에 없으면 exit 1로 배포를 막는다**. 조회 실패도 fail-closed 차단(`:18`). `force=true`면 `reconcile.yml:138`에서 hook 자체를 생략.
- `epr-orphans.sh`: `git ls-tree`로 배포 대상 SHA에서 FQCN의 `.java` 파일과 메서드 정의를 찾고 없으면 출력 + exit 1. **미완료 행이 있다는 이유만으로는 막지 않는다**(고아 예정만 막음).
- 경고만 하는 것이 아니라 **차단**(role이 매핑에 있고 SHA가 바뀐 경우에 한해). 새 role 이름 `kista-worker`·`kista-web`은 `*) continue`로 **검사에서 빠진다**.

### 9. 트리거와 범위 판정

api 워크플로 `Server Deploy`(`api:.github/workflows/server-deploy.yml`):
- `on: push main` + `workflow_dispatch(force, build_only)`(`:7-20`). 경로 필터 없음.
- 잡: `plan`(infra state 조회 -> `plan-deploy.sh`, 수동 실행은 state를 0으로 치환해 전 role을 target으로) -> `deploy-checks`(shellcheck + bats + `check-migrations.sh`) -> `verify`(전체 테스트, 조건부) / `build`(arm64 러너 `ubuntu-24.04-arm`, 이미지 **1개**, 조건부) -> `deploy`(dispatch만 하고 끝, `needs` 모두 success/skipped일 때).
- `plan-deploy.sh:46`: **`pick "$sha" "${role#kista-}"`** — role 이름에서 `kista-`를 뗀 문자열(`api`/`scheduler`/`trading`/`ui`)을 `detect-deploy-scope.sh`의 플래그 키(`config|api|scheduler|trading`)로 쓴다. `config`는 `deploy/server/{docker-compose.yml,roles,bluegreen}`, `deploy/hooks/*`, `deploy/server/caddy/*` 변경 시(`detect-deploy-scope.sh:39-43`).
- `detect-deploy-scope.sh` 규칙(`:20-48`): `trading-core/src/main/*` -> trading; `src/main/resources/db/migration/*` -> api+scheduler; `adapter/in/schedule/*`·`AdminSchedulerController` -> scheduler만; 그 외 `src/main/*` -> api+scheduler; 양쪽 jar 입력(`shared/src/main/*`, gradle 파일, `Dockerfile` 등) -> 전부. 주석 문서 변경(`docs/`, `.github/`)은 아무 플래그도 안 켬 -> `deploy=false`.
- 이미지가 필요한 쪽은 `build=true`(어떤 role이든 SHA가 바뀌면). config만 바뀌면 이미지 빌드 없이 infra가 config SHA로 재적용.

**자동 배포를 잠시 끄는 방법: 문서·스크립트 어디에도 전용 수단이 없다.**
- skip 커밋 토큰(`[skip ci]` 류) 없음, 경로 필터 없음, `DEPLOY_ENABLED` 류 변수 없음(grep 0건). `build_only`는 수동 dispatch 전용 입력이고 push에는 적용되지 않는다(`server-deploy.yml:178`).
- 가능한 수단: (a) GitHub에서 `Server Deploy` 워크플로 disable(문서에 없음, 확인 가능하나 이 경우 verify/build도 멈춰 이미지가 안 생김); (b) 바뀐 경로가 `docs/`·`.github/`뿐인 커밋은 어떤 role도 target이 되지 않아 배포가 안 나간다(`plan-deploy.sh` `deploy=false`); (c) **state와 roles 불일치 상태로 push하면 `build-bundle.sh:17`에서 서버 변경 전에 실패**한다(배포 실패 텔레그램 알림은 옴). (c)는 우연히 안전한 장치일 뿐 의도된 skip이 아니다.
- main에 올라가야만 배포 가능: `resolve-request.sh:35`가 SHA를 앱 `origin/main`의 조상으로 요구한다(브랜치만으로 수동 배포 불가).

수동 특정 SHA 배포: infra `Reconcile App` `workflow_dispatch(app, config, roles="role=sha ...", force)`(`reconcile.yml:11-25`). 단 (1) roles의 role 이름은 state에 이미 있어야 함, (2) 요청이 state보다 **옛것이면 무시**되고 같아도 `changed=false`로 생략(`resolve-request.sh:39-43,70-74`) -> 이 경로로는 롤백 불가, (3) 이미지가 GHCR에 있어야 함(`require_image`).
- api `Server Deploy`의 `workflow_dispatch`는 config·전 role을 이 커밋으로 판정(`:60-62`).

롤백: infra README "수동 복구": `ls -l /opt/<app>/{current,previous}` 후 `nohup bash /opt/kista-infra/bin/reconcile.sh <app> $(basename "$(readlink /opt/<app>/previous)")`, 이어서 `state/<app>.yml`을 실제 적용 SHA로 수동 커밋. 지침서 05 4-3 롤백의 "state 키를 되돌린 뒤 구 이미지 SHA로 3 role을 올린다"에서, **state 되돌리기만으로는 Reconcile App dispatch가 동작하지 않는다**(위 (2)). 서버 `releases/`에 옛 번들이 남아 있으면(KEEP_RELEASES=5) 위 직접 reconcile이 가장 쉽다. api `deploy/server/README.md` 롤백 절은 "state를 그 SHA로 수정·커밋한 뒤 dispatch"라고 적었지만 `resolve-request.sh` 로직상 `state == 요청`이면 `changed=false`라 적용되지 않는다(확실하지 않으나 코드 상 그렇다, 실행 확인은 안 함).

### 10. 지침서 03 요약과 실행 상태 / 바꿀 파일 전수

infra 지침서 `guides/03-instance-topology.md`:
- 5-1: roles에서 사라진 서비스의 컨테이너는 지워지지 않아 `kista-worker`와 옛 scheduler/trading이 동시에 돈다 -> 전환은 자동 배포 금지, 순서 고정(옛 scheduler·trading 정지 -> state 키 커밋 -> reconcile -> Caddy 확인 후 옛 api 정지·삭제 -> ps -a 확인). 롤백은 새 컨테이너 정지 + state 키 복원 + 옛 release reconcile.
- 5-2: 고아 감지 = T8, **완료**(infra `533cc22`, bats 82/82, PROGRESS.md:96,105).
- 5-3: 값이 바뀌는 곳 표(roles worker->web, bluegreen web, compose container_name·200s, Caddy 스니펫 upstream 2개, `KISTA_ROLE=web|worker`는 **env를 코드보다 먼저 배포**, pre-apply 매핑, `reconcile.yml:47` 주석, secrets KIS 키 삭제는 다른 커밋).
- 5-4: Caddy 내부 리스너 `{$API_DOMAIN}, http://:8080 {...}` 로컬 실증 완료.
- 5-5: SSE·재시작 중 응답. 엔진 대역 `lb_try_duration` 5초, `handle_errors`로 503 `application/problem+json` + `Retry-After: 30` 권장(로컬 실증).
- 7절 태스크: T0 완료, T8 완료, T1(24h 측정)~T6·**T7(2 role 전환 배포)은 미완료**(T7은 api 05 구현 뒤). PROGRESS.md:216 기준 03은 T1 다음부터 계속.
- 하지 말아야 할 것(8절): T4(fida 이전)와 T7을 같은 날 하지 않는다. 전환 배포는 매매 시간대 밖·평일 낮 또는 주말. 서버 쓰기는 사용자 승인.

api 지침서 05 13절: P3·P4·P6, 단계 M·X·R·G 남음. api PROGRESS의 "2 role 전환" 관련 상태는 단계 M 브랜치 미착수(이 조사로는 확인 못 함).

#### role 이름 `kista-scheduler`·`kista-trading`이 박힌 줄 (infra 전체, fable-review·specs 제외)

| 파일:줄 | 성격 |
|---|---|
| `infra:state/kista-api.yml:4,6` | **값(state 키)** — 수정 필수 |
| `infra:README.md:30,31` | mermaid 배포 구성도 노드(+ api/scheduler/trading 관련 화살표 `:41-48`) |
| `infra:.github/tests/reconcile.bats` 10,28,36,57,59,60,70,74,88,105,122,145,209,228,273,279,283,284,307,319,374,399 | 픽스처. role 이름·개수(3)·순서가 단언에 박힘 |
| `infra:.github/tests/drift.bats` 16,21,25,29,33,90,103,153,155,158,163,165,166,173,183,185 | 픽스처(고아 시나리오 `kista-scheduler` 포함) |
| `infra:.github/tests/request.bats` 18,27,28,33,36,38,42,97,100,138,147 | 픽스처 + `image_var kista-trading = KISTA_TRADING_IMAGE`(`:28`) |
| `infra:.github/tests/compose-scale.bats:2` | 주석만 |
| `infra:.github/workflows/server-deploy.yml:98,101` | 주석만 |
| `infra:.github/workflows/reconcile.yml:50` | 주석 "role 수(앱 번들이 정함)" — 이름 직접 없음, 03 5-3의 "role 3개" 언급은 `timeout-minutes: 45`와 동행 |
| `infra:docs/superpowers/fable-review/*`, `specs/*` | 문서(정리 대상 아님, 05 T6에서 축약 예정) |

`kista-api`라는 이름은 앱 이름이기도 해서 role로만 쓰인 곳을 따로 구분해야 한다: `infra:README.md:29,40~`·`reconcile.bats`·`drift.bats`·`request.bats`·`state/kista-api.yml`의 role 키 `kista-api`.

#### api 레포 (deploy/ .github/ docs/agents/ README.md src/main/resources trading-core/src/main/resources)

| 파일 | 줄 수 |
|---|---|
| `deploy/server/docker-compose.yml` | 15 |
| `deploy/server/README.md` | 6 (`:3,46,47,48,57,80`) |
| `deploy/server/roles` / `bluegreen` | 2 / 1 |
| `deploy/server/caddy/kista-api.caddy` | 4 |
| `deploy/hooks/pre-apply.sh` | 3 (`:3,13,14`) |
| `.github/scripts/detect-deploy-scope.sh` | 2 (`:7,8`) + 플래그 로직 전체 |
| `.github/scripts/plan-deploy.sh:46` | `${role#kista-}` 키 매핑(이름 직접 없음) |
| `.github/scripts/check-migrations.sh` | 1 (`:7`) + root/trading 디렉토리 적용 범위(D3) |
| `.github/tests/compose-invariants.bats` | 11 (`:8,13,14,16,20,21,23,29,35,39` 등) |
| `.github/tests/deploy-plan.bats` | 6 (`:39,46,53,65,76,84`) |
| `.github/tests/pre-apply.bats` | 3 (`:24,31,37`) |
| `README.md` | 3 (`:29,30,53`) |
| `docs/agents/docker-infra.md` 10, `architecture.md` 2, `commands.md` 1, `constraints.md` 1, `flyway.md` 4, `scheduler-time-table.md` 4, `workflow.md` 1, `workflow-batch.md` 3, `modules/api/web.md` 1, `modules/shared/contract.md` 1, `modules/tradingcore/marketcalendar.md` 1, `notify.md` 2, `vendors/binance.md` 1 | 문서 |
| `src/main/resources/application-prod.yml` 3(`:15,17,20`), `application.yml:73`, `trading-core/src/main/resources/application.yml:3`(`spring.application.name: kista-trading`), `application-local.yml:34`(로그 파일명) | 설정·주석(`application.yml:3`은 값) |
| `src/main/resources/db/migration/V5·V6`, `trading-core/.../V2·V4·V8·V9·V12` | 이미 적용된 마이그레이션(주석 포함 수정 금지, 건드리지 말 것) |

`docs/agents/docker-infra.md`의 위 10줄과 `deploy/server/README.md`는 3 role 전제 서술. 마이그레이션 SQL은 **수정 불가**(checksum).

#### 05 K-3 표(`api:docs/superpowers/fable-review/findings/10-merge-collisions.md:543-562`)와 현재 코드 대조
- 표의 `deploy/server/readiness` 파일은 A3로 이미 없다(compose healthcheck로 대체됨). 표에 "`event_type=deploy-kista-api`"는 이미 `deploy`로 바뀜(`reconcile.yml:9`).
- 표에 빠진 것: **`api:.github/scripts/plan-deploy.sh` + `detect-deploy-scope.sh`의 키 이름 의존성**(표는 적었으나 결과가 가장 위험, 13절 참고), **infra 쪽 테스트 3종**(`reconcile/drift/request.bats`)과 `state/kista-api.yml`, `README.md` mermaid.

### 11. 모니터링

- Prometheus: `api:infra/prometheus/prometheus.yml:5-8` 한 job `kista-app`, 타깃 `host.docker.internal:8080`(**로컬 개발용**). 운영은 scrape 없이 앱이 `micrometer-registry-otlp`로 Grafana Cloud에 60초 간격 OTLP push(`api:deploy/server/README.md` 모니터링 절). infra에는 prometheus 설정이 없다.
- 메트릭 `application` 태그: `management.metrics.tags.application=${spring.application.name}`(`api:src/main/resources/application.yml:70-71`). **root(api·scheduler)는 `kista-api`**(`application.yml:3`), trading-core는 `kista-trading`(`trading-core/.../application.yml:3`). 같은 이미지라 api와 scheduler의 시리즈는 `application="kista-api"`로 합쳐진다(role 구분 불가).
- Grafana 대시보드 `api:deploy/grafana/kista-api-dashboard.json`: `application=\"kista-api\"` 필터가 **15곳**(grep -c, K-3은 17곳이라 적음). trading 패널 없음. 합병 뒤 한 이름이면 web·worker가 한 시리즈로 합쳐진다(K-3 `:560`).
- 외부 하트비트: healthchecks.io(`HEARTBEAT_OPEN_URL`/`CLOSE_URL`/`CRYPTO_URL`, `api:trading-core/src/main/resources/application.yml:106-109`, 값은 `kista-api.env`가 주입) — 개장 22:30·마감 04:30 KST·코인 10분 틱 완료 시 핑. 미설정 시 생략. UptimeRobot -> `https://<API 도메인>/actuator/health` 5분(`api:deploy/server/README.md` 모니터링 절). **전환 중 `kista-trading`을 정지하면 healthchecks 핑이 끊겨 경보가 갈 수 있다**(개장/마감 창 밖이면 grace 안에 복귀).
- 배포 알림: 실패 시 텔레그램(`reconcile.yml:180-188`, infra deploy 실패는 `9a58065`), 드리프트·고아는 `drift-check.sh` 텔레그램.

### 12. 소요 시간·주기

- 게이트: 최대 5분(30회 x 10초)/role, reconcile.yml `timeout-minutes: 45`(`:51`, 주석 "5분 x (교체+롤백) x role 수 + pull·업로드"). 이미지 pull 실패 시 즉시 exit 1.
- compose healthcheck: start_period 180s, interval 30s, retries 3. trading/scheduler `stop_grace_period` 200s(docker stop이 이 값을 기다림), Spring `timeout-per-shutdown-phase` 30s가 먼저 강제 종료할 수 있음(`api:application-prod.yml:12`).
- 03 5-5: worker(지금의 trading) 재시작은 3~4분. blue/green api 교체는 새 컨테이너 헬스(~start_period) 뒤 옛 컨테이너 stop(최대 35s).
- 전체 reconcile 소요 추정: 3 role 변경 시 trading 정지 200s(최대) + 기동 ~3~5분, api blue/green ~3~4분, scheduler stop 200s + 기동 ~3분 -> 약 10~20분(실측 아님, 스크립트 상한 기반 추정).
- 최근 배포 주기(infra `git log` state 커밋, 서브 UTC): 2026-11-02~11-15 사이 `deploy(kista-api)` 커밋이 약 40건/2주(하루 1~5회). 11-13 하루 6건(01 T7 작업), 11-15 01:16 UTC 마지막.
- 서버 EPR 정리 런북(`api:docs/agents/docker-infra.md:121-137`) 요약: 이벤트·리스너를 다른 패키지로 옮기거나 개명한 커밋은 재발행 role(`trading.event_publication`=kista-trading, `public.event_publication`=kista-scheduler) 이미지 교체 직전에 옛 이름 미완료 행을 확인·삭제. 자동 검사는 pre-apply hook(위 8절). SQL: `SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND event_type LIKE '<옛 패키지>.%'` -> 0이 아니면 `DELETE`(알림 유실 공지), `completion_date`로 완료 위장 금지. 리스너만 이동하고 이벤트 FQCN·메서드·멱등이면 `UPDATE ... SET listener_id = '<새>' WHERE listener_id = '<옛>' AND completion_date IS NULL`. `flyway.md`는 root 이동(패키지 루트 분리)은 모든 미완료 행을 고아로 만든다고 경고.

### 13. 지침서 05 4-3절 초안 vs 실제: 불일치·빈칸

(a) 전제 확인(맞는 것): reconcile이 `roles`의 서비스만 올리고 사라진 서비스는 안 지움(`--remove-orphans` 없음, `reconcile.sh:142`); `build-bundle.sh:17`의 검사; T8 고아 감지는 이미 있음.

(b) 불일치·빈칸 (위험 순):
1. **`plan-deploy.sh:46`의 `${role#kista-}` 키**: state 키를 `kista-web`·`kista-worker`로 바꾸면 플래그 키는 `web`·`worker`인데 `detect-deploy-scope.sh`는 `api|scheduler|trading|config`만 출력한다(`:14-18,50-54`). `pick`이 "플래그 없음"으로 판정하면 **role SHA가 영원히 옛 값에 머물고 `build=false`, `config`만 새 SHA로 가는 배포**가 된다 — 새 compose + 옛 이미지(KISTA_ROLE 없음)가 나가며 pre-apply hook도 `hook_roles` 비어 생략됨. 4-3의 "state 키를 바꾼다" 시점은 `detect-deploy-scope.sh`·`plan-deploy.sh`·`deploy-plan.bats` 갱신 커밋이 api main에 먼저 들어간 **뒤**여야 하고, 두 레포 변경이 시점 순서를 가진다. 4-3에는 이 선행 조건이 없다.
2. **"state 키를 바꾼다"의 실제 범위**: 1파일(`infra:state/kista-api.yml:4-6`)이 값이고, 같은 커밋에서 **테스트 3개**(`reconcile.bats`·`drift.bats`·`request.bats`)가 role 3개 픽스처로 박혀 있어 infra CI(`server-deploy.yml` `checks`)가 깨진다 -> 실제로는 infra에서 파일 4개(+`README.md` 구성도). 또한 state는 `reconcile.yml`만 쓰는 파일이라 수동 커밋 시 동시에 도는 자동 state 커밋과 충돌할 수 있다(앱별 `concurrency: deploy-<app>`는 있으나 수동 커밋은 그 잠금 밖).
3. **자동 배포를 끄는 방법이 없다**(9절). 4-3 서두는 "커밋을 판정에서 제외하거나 자동 배포를 잠시 끈다"고 하나 스크립트에 skip 장치가 없다. 전환 커밋(`src/main` 포함)이 main에 오르면 plan이 api·scheduler·trading을 target으로 잡고 dispatch한다. state 키가 아직 옛 이름이면 `build-bundle.sh:17`에서 **서버 변경 전 실패**(이미지는 이미 빌드됨 -> 수동 배포용 이미지가 생긴다). state를 먼저 바꾸면 위 1번 사고. 즉 현실적 순서는 (i) detect/plan 선행 커밋 -> (ii) 전환 커밋 push(자동 dispatch는 mismatch로 안전하게 실패, 이미지 확보) -> (iii) 수동 단계. 이 순서는 4-3에 없다.
4. **수동 정지 명령**: 스크립트는 없고 `docker` 직접 명령뿐이다. trading·scheduler는 `container_name`이 있어 `docker stop kista-scheduler kista-trading`(기본 timeout이 컨테이너 StopTimeout=200s, `reconcile.sh:173` 주석) 후 `docker rm`. 옛 `kista-api`는 container_name이 없어 `docker ps -aq --filter label=com.docker.compose.project=kista-api --filter label=com.docker.compose.service=kista-api`로 찾는다(`kista-api-kista-api-N`). `docker compose -p kista-api stop`은 release의 compose·env 인자가 필요해 쓰기 번거롭다. 4-3의 "docker ps에 두 이름이 없는 것을 확인" OK.
5. **자동 롤백이 이름 변경 배포에서는 동작하지 않는다**: `reconcile.sh:76`은 `compose "$prev" up -d --no-deps "$role"`로 직전 release로 되돌리는데 직전 release의 compose에는 `kista-worker`·`kista-web`이 없다 -> "no such service"로 `::error::... 롤백 실패 — 수동 복구 필요` exit 2. `kista-web`도 첫 배포는 실행 중 컨테이너가 없어 blue/green이 아닌 stop-first 경로(`:136-148`)라 롤백 대상이 같다. 4-3 롤백(새 role 정지·삭제, state 키 복원, 구 SHA로 3 role 올림)은 맞지만 **직접 `reconcile.sh kista-api <옛 release id>` 서버 호출**이어야 한다(Reconcile App dispatch는 불가: state가 요청보다 새것이거나 같으면 변경 없음). 옛 release 번들은 KEEP_RELEASES=5로 보존되고 `previous` 심링크도 있으나, 전환 배포 성공 시 `previous`가 옛(3 role) release를 가리킨다는 보장은 `:184` 로직상 맞다.
6. **4-3 4단계 "reconcile. roles 적용 순서 worker -> web"**: 사실. 단 `build-bundle.sh:17`은 roles 파일과 state **키 집합**만 비교(`sort` 후 cmp)하므로 순서는 roles 파일이 정한다. 새 role마다 이미지 변수는 `image_var`가 이름에서 자동 생성(`KISTA_WORKER_IMAGE`, `KISTA_WEB_IMAGE`)하므로 compose 변수명을 그에 맞춰야 한다(맞지 않으면 `${...:?}` 필수 보간 실패, `compose pull`에서 실패 -> `:54` "아무것도 변경하지 않음" exit 1).
7. **5단계 "EPR 재발행 처리 확인"의 hook 공백**: `pre-apply.sh:15` `*) continue`가 `kista-worker`·`kista-web`을 건너뛴다. 전환 배포는 hook이 아무것도 검사하지 않는다(role 매핑 갱신 전). hook을 먼저 고쳐 `kista-worker -> public`으로 만들면, 전환 시점 `public.event_publication`(2번 이관 뒤)의 이벤트 행이 worker 소스에 있는지 검사한다. 4-3은 2번 EPR 이관을 4번 reconcile 앞에 두므로 hook이 이관된 행을 본다 — 맞는 순서. 단 hook 조회 대상은 `public`뿐이고 원본 `trading.event_publication`은 안 봄(이관 전 정지 상태에서 사람이 SQL로 확인).
8. **6단계 "Caddy가 가리키는지 확인"은 자동으로 일어난다**: 스니펫 설치·reload는 reconcile의 모든 role 통과 직후 같은 실행에서 수행(`:151-171`). 단 그 사이(옛 trading 정지 ~ 새 worker healthy + 스니펫 reload)에 `@trading` 경로는 `kista-trading:8080`으로 가 502/503: `lb_try_duration 120s`로는 worker 기동(3~5분)을 못 덮는다. 매매 화면은 이미 "응답하지 않는다"고 4-3에 적혀 있어 허용됨. 03 5-5의 `lb_try_duration` 5초 + `handle_errors`를 같은 스니펫에 넣을지 결정 필요.
9. **7단계(옛 kista-api 정지·삭제)와 고아 알림**: 4~6단계 동안 옛 `kista-api`(실행 중)는 roles에 없어 `drift-check.sh:37-43`이 15분 안에 `kista-api: 고아 kista-api`를 텔레그램으로 알린다. 새 role 이름 `kista-web`이 뜬 뒤 옛 서비스명이 같은 `kista-api`지만 roles에 없으므로 고아. 예상된 알림(전환 중 노이즈)이며 `.drift-last`로 1회만. 또한 reconcile 중이 아닐 때 정지(`docker stop`)만 하고 `rm`하지 않은 컨테이너는 알리지 않음.
10. **전환 순서 위반 시 지침서 4-3의 "worker가 둘 떴다"는 감지 수단이 없다**: 고아 감지는 알림뿐이고 중단 장치가 없다. 단계 1에서 `docker ps`로 직접 확인해야 한다.
11. **hook_roles/이미지 일치**: 새 role의 이미지는 `ghcr.io/narafu/kista-api:<sha>` 같은 한 저장소 하나(`IMAGE_REPO`, `reconcile.yml:105`)이고 role별 SHA만 다르다. 전환 시 `kista-web`·`kista-worker`는 **반드시 같은 새 SHA**여야 한다(1번의 위험으로 state에 옛 SHA가 남으면 옛 이미지가 새 role 이름으로 뜬다).
12. **전환 배포는 compose 변경이라 매매 시간대 가드는 안 걸린다**(앱 reconcile엔 가드 없음). 지침서가 말하는 "매매 시간대 밖"은 사람이 지켜야 한다(`trading-guard.sh`는 infra 배포 전용). 03 8절은 평일 낮·주말을 권장.
13. **`stop_grace_period`와 compose 단건 up**: worker가 stop-first이므로 첫 전환 배포에서 옛 container_name `kista-trading`/`kista-scheduler`와 새 `kista-worker`는 이름이 달라 충돌하지 않는다. 호스트 메모리: 전환 중 옛 kista-api(2048m) + 새 web(미정) + worker가 동시에 떠 합계가 호스트 한도를 넘을 수 있다(주석 `:106-109`). 새 compose의 mem_limit 합계와 중첩 구간을 4-3에 적어야 한다.
14. **지침서 4-3 사전 확인에 빠진 것**: ① `trading.scheduler_locks` 확인은 있으나 `public.scheduler_locks`도 있다(DB 스키마 5분리 문서: public·trading 각자 사본). ② healthchecks.io 핑 끊김 경보(11절). ③ infra drift-check 알림 노이즈. ④ 전환 직전 `/opt/kista-api/releases/` 최신 release 보존 확인(롤백용).
