# 27. S11 유일 인덱스 사전 조사 — orders·strategy_cycle 쓰기 경로 전수

조사 기준: 2026-11-15 HEAD(125c07d6b), 읽기 전용(정적 코드 분석). 실행·DB 조회는 하지 않았다("미확인"은 실행이 필요한 것).
경로 접두사 `tc` = `trading-core/src/main/java/com/kista/tradingcore/trading/application/service`, `pp` = `.../trading/adapter/out/persistence`.

## 0. 먼저 읽을 결론

| # | 판정 | 요약 |
|---|------|------|
| 1 | 위반 가능(높음) | VR 롤오버·VR 재설정이 "옛 사이클 markEnded(UPDATE) → 새 사이클 INSERT"를 한 트랜잭션에서 dirty checking으로 한다. Hibernate는 commit flush 때 INSERT를 UPDATE보다 먼저 보내므로 `(strategy_id) WHERE end_date IS NULL` 인덱스가 걸리면 위반 |
| 2 | 위반 가능(높음) | PRIVACY 개별 캡(`applyIndividualCap`)이 같은 leg의 PLANNED를 cancelPlanned(UPDATE)한 뒤 같은 leg로 INSERT. 마지막 cancel은 flush 없이 INSERT가 먼저 나가 orders 인덱스 위반 |
| 3 | 의심(낮음) | VR bootstrap 매수(`VR_BUY_01`, AT_CLOSE LOC)와 사다리 매수(`VR_BUY_01`, AT_OPEN LIMIT)가 같은 leg 이름. 인덱스는 timing을 안 보므로 같은 (cycle, date)에 둘 다 살아 있으면 위반 |
| 4 | 안전하나 주의 | 정상 경로에서 "같은 슬롯에 살아 있는 주문 둘"이 되는 곳은 찾지 못했다. 나머지 경로는 아래 표 참고 |

## 1. 상태 집합

`OrderStatus`(`shared/.../kernel/OrderStatus.java`): PLANNED, SUBMITTING, PLACED, FILLED, PARTIALLY_FILLED, FAILED, CANCELLED.

| 인덱스 "살아 있음" | PLANNED SUBMITTING PLACED PARTIALLY_FILLED FILLED |
|---|---|
| 죽은 상태 | FAILED, CANCELLED(취소 전 부분 체결도 CANCELLED 유지 — `TradingReporter.java:126`) |
| 앱의 "열린 주문" 집합(`OPEN_STATUSES`, `pp/OrderPersistenceAdapter.java:34`) | PLANNED, SUBMITTING, PLACED — **FILLED·PARTIALLY_FILLED가 빠져 있다**. `excludeExisting`·재계산 skip·수동 실행 이중 검사가 이 집합만 보므로 인덱스의 살아 있는 집합이 더 넓다 |

nullable: `OrderEntity` trade_date·strategy_cycle_id·order_leg 모두 `nullable=false`, V1__init.sql:94-110도 NOT NULL(order_leg는 DEFAULT 'UNKNOWN'). null 불가. `PlannedOrder`·`Order` 컴팩트 생성자가 null/blank leg를 'UNKNOWN'으로 정규화(`PlannedOrder.java:27`, `Order.java:43`). 이후 migration에서 이 컬럼들을 바꾼 것은 없다(V8은 quantity 타입만).

## 2. 조사 A — orders 쓰기 경로

쓰기 진입점은 `OrderPersistenceAdapter` 한 곳뿐이다(native INSERT/UPDATE/JdbcTemplate 없음. orders를 직접 쓰는 SQL은 `scripts/dev-seed/dev-seed.sql:142,308,405`와 V12:14 DELETE뿐). `@Modifying`은 `deleteAllByStrategyCycleIdAndTradeDateAndStatus` 하나(JPQL 벌크 DELETE).
어댑터 public 쓰기 메서드: `saveAll`, `save`, `markSubmitting`, `markPlaced`, `cancelPlanned`, `markCancelled`, `markFailed`, `markFilled`, `deletePlannedByCycleAndDate`. UPDATE 계열은 모두 엔티티 조회 후 `repository.save`(dirty checking, 즉시 flush 아님).

### 2-1. INSERT 경로

| 호출처 파일:줄 | 흐름 | status | leg 출처 | 선행 정리 | 판정 |
|---|---|---|---|---|---|
| `tc/support/TradingOrderPlanner.java:32` (`saveAll`) ← `TradingCandidatePlanner.java:318` | 마감 배치 `planAll`→`allocateAndSave`, 개장 배치 `TradingService.java:406`→같은 메서드 | PLANNED | 전략 커널 `PlannedOrder.of(..., leg)`. UNKNOWN은 `TradingOrderPlanner.java:25-28`이 저장 거부(`validateConcreteOrderLegs` `:201`도 선검증) | 락 안 재조회 `excludeExisting(slot.orders(), findPlannedOrPlacedByCycleAndDate)`(`:296`) = (timing,direction,leg) 동일 슬롯 제외. 지우거나 상태 바꾸지 않음. 계좌 예산 락·한 트랜잭션 아님(saveAll 자체 트랜잭션) | 안전. 단 `excludeExisting`이 FILLED/PARTIALLY_FILLED·timing을 인덱스와 다르게 본다 → 의심3 |
| 같은 포트 ← `ManualTradingService.java:123` | 수동 실행(DCA 제외 `:56`) | PLANNED | 위와 동일 | 저장 직전 `findPlannedOrPlacedByCycleAndDate` 비어 있음 검사(`:70`, 예산 락 안 `:109`), 있으면 AlreadyOrderedToday | 안전(FILLED 행은 못 봄 → 같은 trade_date에 FILLED가 있는 시점의 수동 실행은 미확인. trade_date는 nextTradeDate라 정상은 분리) |
| 같은 포트 ← `BuyOrderPriceCapper.java:131` (`applyIndividualCap`) | 접수 직전 BUY 재캡, PRIVACY(`capsIndividualOrders()`) | PLANNED | 원본 행 leg를 그대로 `corrected`가 유지(가격만 치환, 개수·순서 동일 검증 `:114`) | 변경된 행마다 `cancelPlanned(id)`(`:125`, UPDATE→CANCELLED) 후 **같은 leg**로 INSERT. 같은 `@Transactional capIfNeeded`(`:67`). flush 사이에 쿼리 없음 | **위반 가능(높음)** — 아래 2-3 |
| 같은 포트 ← `BuyOrderPriceCapper.java:151` (`applyBatchCap`) | INFINITE/VR BUY 재캡(사다리 통째 교체) | PLANNED | 재산정 결과의 leg(INFINITE_*·VR_BUY_nn) | 전부 `cancelPlanned`(`:140`) 후 `findPlannedOrPlacedByCycleAndDate`(`:144`, JPQL이라 auto flush로 UPDATE 먼저 반영) → `excludeExisting`으로 이미 PLACED/SUBMITTING인 슬롯 제외 → INSERT | 안전(중간 JPQL 조회 덕). 이 flush가 우연히 순서를 지킨다는 점이 취약 — 조회가 사라지면 2-3과 같은 위반 |
| `ManualTradeCorrectionService.java:115` (`saveAll`) | 관리자·사용자 수동 체결 보정 | FILLED | `Order.java:80` 팩토리가 항상 `UNKNOWN_LEG` | 없음 | 안전 — `order_leg <> 'UNKNOWN'` 조건으로 인덱스 밖 |
| `CryptoDcaRoundExecutor.java:183` (`save`) | 코인 DCA 틱 | PLANNED→(185)SUBMITTING | `rule.orderLeg()` = DCA 접두사+ruleKey(`DcaRule.java:91`) | 대상 선정 `targets()`(`:148-153`)가 같은 leg의 PLANNED/SUBMITTING 있으면 skip, PLACED/FILLED/PARTIALLY_FILLED 있으면 recordOrdered 후 제외. FAILED·CANCELLED만 있으면 재시도 INSERT(죽은 행이라 허용) | 안전. 동시 틱 레이스는 인덱스가 오히려 막아 준다(예외 처리 경로는 미확인) |

### 2-2. UPDATE·DELETE 경로 (상태 전이)

| 호출처 파일:줄 | 흐름 | 전이 | 선행 조건 | 판정 |
|---|---|---|---|---|
| `OrderSubmitter.java:41`, `CryptoDcaRoundExecutor.java:185` markSubmitting | 접수 선점 | PLANNED→SUBMITTING | 행 잠금 + PLANNED만(`requireStatus`) | 안전(live→live) |
| `OrderSubmitter.java:165,175`, `OrderSubmissionReconciler.java:89`, `CryptoDcaRoundExecutor.java:193`, `CryptoOrderSynchronizer.java:82` markPlaced | 접수 성공·복구 | SUBMITTING→PLACED | 행 잠금 + SUBMITTING만(이미 PLACED+같은 접수번호는 멱등) | 안전(live→live) |
| `OrderSubmitter.java:108`, `OrderSubmissionReconciler.java:85`, `CryptoDcaRoundExecutor.java:201`, `CryptoOrderSynchronizer.java:77` markFailed | 확정 거절 | SUBMITTING→FAILED | 행 잠금 + SUBMITTING만 | 안전(live→dead) |
| `BuyOrderPriceCapper.java:125,140`, `OrderCancelStateWriter.java:32`(←`OrderCancelService.java:175`), `CryptoOrderSynchronizer.java:69` cancelPlanned | 재캡·사용자 취소·코인 복구 | PLANNED→CANCELLED | 행 잠금 + PLANNED만 | 안전(live→dead). 되살리는 경로 없음(`OrderPersistenceAdapterDbTest.java:89` 테스트로 고정) |
| `OrderCancelStateWriter.java:38`(←`OrderCancelService.java:134,211`), `TradingReporter.java:137`, `CryptoOrderSynchronizer.java:132` markCancelled | 증권사 취소·미체결 정리 | any→CANCELLED(FILLED·PARTIALLY_FILLED는 유지) | 상태 무잠금(find→save) | 안전(→dead) |
| `OrderCancelStateWriter.java:44`, `TradingReporter.java:126` markFilled(…,CANCELLED) | 취소 전 부분 체결 기록 | CANCELLED 유지 | — | 안전 |
| `TradingReporter.java:149` markFilled(FILLED/PARTIALLY_FILLED) | 마감 리포트(재개 포함) | PLACED→FILLED/PARTIALLY | `markFilledOrders`가 `cancelledOrders`(당일·현재 사이클 CANCELLED)를 `cancelledIds`로 제외(`:134`), externalOrderId 없는 행 건너뜀. 단 mainOrders는 `findByStrategyId`(전 사이클)라 다른 사이클의 CANCELLED 행 처리 여부는 **미확인** | 안전(dead→live 경로 못 찾음, 사이클 경계 케이스만 미확인) |
| `CryptoLedgerRecorder.java:60` markFilled(FILLED) | 코인 체결 반영 | PLACED→FILLED | `settle()`은 PLACED/SUBMITTING 조회분만 | 안전 |
| `OrderCancelStateWriter.java:26`(←`OrderCancelService.java:92`) deletePlanned | 일시중지·취소 일괄 삭제 | PLANNED 행 DELETE | **JPQL 벌크 DELETE**(`OrderJpaRepository.java:30`), 별도 `@Transactional`, 같은 트랜잭션에 INSERT 없음 | 안전(벌크 쿼리는 실행 시점에 flush·즉시 실행되고 이후 INSERT와 순서 문제 없음) |

정정 주문(메리츠/토스/KIS modify·amend)이 orders 행을 UPDATE/INSERT하는 경로는 없다. 브로커 포트는 `cancel`만 있고(`BrokerOrderCorrectionPort`) 재주문은 새 PLANNED 행이다. 부분 체결 뒤 잔량 재주문 경로도 없다(부분 체결 행은 마감 리포트가 PARTIALLY_FILLED로 기록하고 끝, 같은 슬롯 재INSERT 없음). 수동 실행·캡 보정·코인 DCA는 위 표대로 같은 leg를 재사용하지만 모두 "기존 행을 죽인 뒤" 또는 "죽은 행만 있을 때"에만 새 행을 넣는다 — 순서 문제는 2-3 하나.

### 2-3. 위반 가능 항목 상세

**(의심1) PRIVACY 개별 캡**: `BuyOrderPriceCapper.java:122-131`. i번째 변경 행에서 `cancelPlanned(id_i)` 호출 → `OrderPersistenceAdapter.lockOrConflict`가 `entityManager.flush()` 후 refresh하므로 앞선 cancel UPDATE는 반영되지만, **마지막 cancelPlanned의 UPDATE는 pending**. 이어 `savePlannedOrders`→`repository.saveAll`은 `persist`(UUID v7, IDENTITY 아님 → INSERT 지연). `@Transactional capIfNeeded` commit flush에서 ActionQueue 순서가 INSERT→UPDATE라 새 행 INSERT 시점에 옛 행(같은 leg)이 아직 PLANNED → 유일 인덱스 위반. 미확인: 실제 DB 실행(`OrderPersistenceAdapterDbTest`류 DB 테스트로 재현 가능). 수정 방향 후보: cancel 직후 `entityManager.flush()` 또는 어댑터 `cancelPlanned` 끝에 `repository.flush()`.

**(의심2) 같은 이름 leg·다른 timing**: `VrStrategy.java:101-102` bootstrap = `VR_BUY_01`(LOC, AT_CLOSE), `VrStrategy.java:170,177` 사다리 = `VR_BUY_01..`(LIMIT, AT_OPEN). `TradingOrderSlots`는 (timing,direction,leg)로 구분하지만 인덱스는 (cycle,date,leg)라 구분 못 한다. 한 사이클의 같은 trade_date에 두 모드가 동시에 살아 있을 수 있는 시나리오는 정상 흐름에서 못 찾음(개장 패스는 AT_OPEN만, 마감 패스는 AT_CLOSE만 생성하고 holdings=0·value=0 판정이 같은 날 안 바뀜). 다만 `VrStrategy.buildOrders:25-30`의 "holdings>0 드리프트 시 매수만 bootstrap 대체"는 AT_OPEN 사다리 SELL과 BUY bootstrap이 공존하는 분기라 하루 안에 AT_OPEN 매수 사다리가 먼저 생겼다가 마감에 bootstrap으로 바뀌는 조합은 **미확인**. 인덱스를 (…,timing,order_leg)로 하거나 VR leg 접두사를 분리하면 해소.

**(의심3) 인덱스가 FILLED·PARTIALLY_FILLED를 살아 있음으로 본다**: 재계산·이중 실행 검사는 PLANNED/SUBMITTING/PLACED만 본다. 같은 (cycle,trade_date)에 FILLED 행이 생긴 뒤(마감 리포트 06:00 KST) 같은 trade_date로 재계획이 일어나는 시점이 있으면 위반. 정상 시간표상 trade_date가 갈려 발생하지 않는 것으로 보이나(마감 배치는 당일, 리포트는 익일 새벽에 당일분 기록) 확정 못 함 — **미확인**(`SessionTradeDates.nextTradeDate`와 `LocalDate.now(clock)` 경계, 04:00~리포트 사이 수동 실행).

(d) 한 번의 saveAll 안 중복 leg: INFINITE·VR·PRIVACY·DCA 모두 접두사+순번(`PlannedOrder.leg`, VR은 `merged.size()+1`, PRIVACY는 `assignSequentialLegs`)이거나 고정 이름(`INFINITE_FIRST_BUY`, `*_MOC_SELL` 등)이라 한 계획 안에서 유일. `INFINITE_CORRECTION_01~03`은 `addCorrectionOrder`가 1..3 고정. 단 INFINITE `EARLY_MERGED_BUY`와 `EARLY_AVG_BUY`는 이름이 달라 인덱스는 못 잡는다(정책 중복은 범위 밖).

## 3. 조사 B — strategy_cycle 쓰기 경로

`StrategyCyclePersistenceAdapter`(`pp/`)만 쓴다: `save`(INSERT, id=null→persist), `markEnded`(`mutate`→dirty checking UPDATE), `updateStrategyVersion`/`updateStartAmount`(UPDATE), `deleteBy{Strategy,Account,User}Id`(벌크 soft delete UPDATE). **end_date를 NULL로 되돌리는 코드는 없다**(`setEndDate` 호출은 `markEnded`·`toEntity` 둘뿐). 루트(:api) 모듈은 strategy_cycle을 쓰지 않는다.

| 호출처 파일:줄 | 흐름 | 쓰기 | 순서·트랜잭션 | 판정 |
|---|---|---|---|---|
| `StrategyCreationPersister.java:69` | 전략 등록 | INSERT(열린 사이클) | 신규 strategy id라 충돌 없음 | 안전 |
| `CycleSnapshotCreator.java:47` `createCycleAndSnapshot` ← `CycleRotationService.java:96`(INFINITE·PRIVACY 회전) | 사이클 회전 | INSERT | 옛 사이클 `markEnded`는 앞서 `CyclePositionPersistor.java:65`에서 `transactionOperations`로 **이미 커밋**됨 → 새 INSERT는 별도 트랜잭션 | 안전. 실패 시 `CycleRotationService` catch가 PAUSE(좀비 방지) — 인덱스 위반도 같은 경로로 전략을 PAUSE시키므로 이중 회전이 생기면 건강한 전략이 멈춘다(재진입은 `prevHadHoldings` 판정으로 막혀 있어 정상 흐름엔 없음) |
| 같은 메서드 ← `StrategyService.java:195` `reopenCycleIfEnded` | 재개 | INSERT | 종료 사이클은 그대로 두고 새 사이클 INSERT, 락 없음 | 안전. 동시 재개 두 번이 지금은 열린 사이클 둘을 만들 수 있는데 인덱스가 둘째를 막는다(예외 메시지 매핑은 미확인) |
| `CycleSnapshotCreator.java:81-84` `rolloverVrCycle` ← `VrCycleRolloverService.java:154` | VR N주 롤오버 | `markEnded`(UPDATE) → `createVrCycleAndSnapshot`(`save` INSERT, cycle_position INSERT, VR 상세 `save`) | **한 `@Transactional`**, markEnded는 dirty checking(`mutate`→`repository.save(managed)`), 새 사이클은 persist 지연. 둘 사이에 JPQL/native 쿼리 없음 → commit flush에서 INSERT가 UPDATE보다 먼저 | **위반 가능(높음)** |
| `CycleSnapshotCreator.java:89-101` `reconfigureVrCycle` ← `VrReconfigureService.java:125` | VR 운영 중 재설정 | `markEnded`(`:98`) → `createVrCycleAndSnapshot` | 한 `@Transactional`, `findLatestOne` 조회는 markEnded 앞이라 flush 보장 못 함 | **위반 가능(높음)** — 롤오버와 같은 구조 |
| `CycleSnapshotCreator.java:104-120` `reconfigureVrCycleBeforeStart`, `CapitalLedgerService.java:101,164`, `StrategyUpdateWriter.java:124` | 시작 전 재설정·원장·시드 수정 | UPDATE(version, start_amount)만 | 새 사이클 없음 | 안전 |
| `CycleCloser.java:22`(←`ManualTradeCorrectionService.java:96`), `CyclePositionPersistor.java:65` | 청산 종료 | `markEnded`만 | 새 INSERT 없음(별도 트랜잭션 또는 PAUSE 후 재개가 새로 연다) | 안전 |
| `StrategyStateWriter.java:42`, `UserTradingDataEraseService.java:65` | 전략 삭제·사용자 삭제 | soft delete 벌크 UPDATE | `delete`는 `@Transactional`로 position→cycle→strategy 순. 종료 사이클을 별도로 지우는 순서는 없고 **한 번에 전부** deleted_at 세팅 | 안전(deleted_at 찍히면 인덱스 조건에서 빠져 오히려 완화) |
| 보관/복원 | `archive`는 strategy 상태만(`StrategyStateWriter.java:30-34`), 사이클 건드리지 않음. 복원 경로 없음(보관은 한 방향) | — | — | 안전 |

(B-3) end_date NULL 복귀: 없음. (B-4) 열린 사이클 없는 전략은 회전 실패·NONE·원장 1주 미만 시 "종료 사이클만 남은 PAUSED 전략"으로 존재하고, 인덱스 조건(`end_date IS NULL`)에 안 걸린다. 종료된 사이클은 soft delete 전용 순서가 없어 전략 삭제 때 열린 사이클과 함께 같은 UPDATE로 지워진다.

핵심 흐름(롤오버): `CycleSnapshotCreator.rolloverVrCycle`(`:81`) → `strategyCyclePort.markEnded(endedCycleId,…)`(`:82`, 어댑터 `mutate`로 엔티티 조회·setEndDate·save, SQL 미발행) → `createVrCycleAndSnapshot`(`:66`) → `strategyCyclePort.save`(`:69`, persist) → `cyclePositionPort.save`(persist) → `strategyCycleVrPort.save`(assigned id라 `merge`, SELECT만 — `em.find`류는 auto flush를 일으키지 않음) → 메서드 종료 시 commit flush. Hibernate 실행 순서: INSERT들 → UPDATE → DELETE. 따라서 새 사이클 INSERT 시점에 옛 사이클이 아직 `end_date IS NULL`. `DEFERRABLE`은 partial unique index에 쓸 수 없으므로 코드에서 `markEnded` 직후 flush하거나 어댑터 `markEnded`가 `saveAndFlush`를 써야 한다.

## 4. 조사 C — 기존 테스트 기반

### 4-1. persistence 테스트와 FK 선행 행 방법

| 파일 | 방식 |
|---|---|
| `trading-core/src/test/.../trading/adapter/out/persistence/OrderPersistenceAdapterDbTest.java` | `DataJpaTestBase` 상속, `@BeforeEach setUp()`(`:43-67`)이 `@Autowired JdbcTemplate`로 accounts→strategy→strategy_version→strategy_cycle 2건 INSERT. 헬퍼 `insertOrderForAccount`(`:206-218`), `insertOrderCreatedAt`(`:170`), `insertWithId`(`:311`), `insertAccount`(`:220`). 전부 `@Transactional` 롤백 |
| `.../StrategyCyclePersistenceAdapterTest.java` | 같은 방식. 헬퍼 `insertStrategy`(`:50`), `insertCycle`(`:62`) |
| `.../CyclePositionPersistenceAdapterTest.java`, `CycleCapitalEventPersistenceAdapterTest.java`, `CyclePositionDcaInclusionDbTest.java`, `StrategyPersistenceAdapterTest.java`, `application/service/CyclePositionPersistorDbTest.java`, `CycleSnapshotCreatorDbTest.java` | 같은 JdbcTemplate INSERT 패턴(strategy_cycle 행은 각 `:435/606`, `:51`, `:77`, `:214`, `:90`, `:81`) |
| 루트 `src/test/.../admin/AdminInternalApiIntegrationTest.java:94` | users부터 INSERT(루트 테스트) |

커밋형 `*IT`(@Tag("integration")) 예시: `trading-core/src/test/.../trading/adapter/in/event/UserCascadeEndToEndIT.java` — `@BeforeEach`(`:68-84`)가 JdbcTemplate으로 accounts→strategy→strategy_version→strategy_cycle→orders→user_notify_profile을 INSERT(커밋), `@AfterEach`(`:86-94`)가 orders→strategy_cycle→strategy_version→strategy→accounts→user_notify_profile→event_publication 순으로 DELETE. 또 `CryptoLedgerRecorderIT.java:66-80`(`DataJpaTestBase` 상속, 실 어댑터 주입, 커밋 여부는 미확인)이 `OrderPort`로 실제 save→markSubmitting→markPlaced를 돈다.
**인덱스 검증 canary**: `CycleSnapshotCreatorDbTest`는 `@Transactional(NOT_SUPPORTED)`로 트랜잭션을 끄고 실 어댑터로 `rolloverVrCycle`을 커밋한다(`:101-108`, 테스트 `rolloverVrCycle_commitsEndAndNewCycle_whenAllSucceed`). 인덱스를 걸면 의심1이 맞는지 바로 드러나는 기존 테스트다(`cyclePositionPort`·`VrPort`는 mock이라 사이클 INSERT/UPDATE 순서만 본다).

### 4-2. 인덱스를 걸면 깨질 테스트 후보

| 파일:줄 | 내용 | 깨지는 인덱스 |
|---|---|---|
| `OrderPersistenceAdapterDbTest.java:62-66` (`setUp`) | 같은 `strategyId`에 `cycleId`·`otherCycleId` 둘 다 end_date NULL로 INSERT → **클래스 전 테스트** setUp에서 실패 | strategy_cycle |
| `StrategyCyclePersistenceAdapterTest.java:84-85`, `:136-137` | 같은 `strategyA`에 열린 사이클 `cycleA1`·`cycleA2`(deleted 아님) | strategy_cycle |
| `StrategyCyclePersistenceAdapterTest.java:87,139` | deleted 사이클은 조건 밖이라 무관 | — |
| `OrderPersistenceAdapterDbTest.java:333-340` (`submittingCryptoOrderCanBePlacedOrFailed`) | `saveCrypto()` 2회(`:325-329`, 같은 cycle·2026-10-18·leg `DCA_BUY`): a는 PLACED로 살아 있는데 b를 PLANNED로 INSERT | orders |
| `OrderPersistenceAdapterDbTest.java:117-121` 외 `insertOrder*` 사용 다수 | 모두 leg `UNKNOWN`이라 인덱스 밖 — 영향 없음 | — |
| `OrderPersistenceAdapterDbTest.java:71-86,89-102,472-515` | 테스트당 1건 저장 → 영향 없음 | — |
| `CryptoLedgerRecorderIT.java:89,168` | leg가 서로 다름(`DCA_1111…`·`DCA_2222…`) → 영향 없음 | — |
| `StrategyPersistenceAdapterTest.java:214-220` | 사이클 1·주문 1(CANCELLED) | — |

단위 테스트(mock 포트)는 DB를 안 타 영향 없음. 다른 DB 테스트의 strategy_cycle INSERT는 전략당 1건으로 보인다(`CyclePositionPersistenceAdapterTest`는 `insertChain`이 호출마다 새 strategy 생성 — 확인함). 이 grep은 `INSERT INTO ... strategy_cycle|orders` 문자열과 `adapter.save/saveAll` 호출 기준이라 헬퍼 경유 다건 저장은 일부 놓쳤을 수 있다(미확인: `*DbTest` 전수 실행 필요).

## 5. 운영 적용 전 확인할 것(미실행)

운영 DB 읽기 전용 조회(`ssh kista-server`):
- `SELECT strategy_id, count(*) FROM trading.strategy_cycle WHERE end_date IS NULL AND deleted_at IS NULL GROUP BY 1 HAVING count(*) > 1;`
- `SELECT strategy_cycle_id, trade_date, order_leg, count(*) FROM trading.orders WHERE status IN ('PLANNED','SUBMITTING','PLACED','PARTIALLY_FILLED','FILLED') AND order_leg <> 'UNKNOWN' GROUP BY 1,2,3 HAVING count(*) > 1;`
기존 중복이 있으면 `CREATE UNIQUE INDEX`가 실패하므로 먼저 정리해야 한다(과거 INFINITE 수동 실행·재캡 이력 때문에 orders 쪽 중복 가능성은 미확인).

## 6. 의심·위반 가능 항목 요약

| 순위 | 위치 | 판정 | 필요한 조치 |
|---|---|---|---|
| 1 | `CycleSnapshotCreator.java:81-84` rolloverVrCycle, `:89-101` reconfigureVrCycle | 위반 가능(높음, INSERT가 UPDATE보다 먼저 flush) | markEnded 직후 flush 보장(어댑터 `saveAndFlush` 또는 `EntityManager.flush()`). 검증은 `CycleSnapshotCreatorDbTest` |
| 2 | `BuyOrderPriceCapper.java:122-131` applyIndividualCap (PRIVACY) | 위반 가능(높음, 마지막 cancelPlanned flush 없음) | `cancelPlanned` 끝 flush. `applyBatchCap`(`:144` 조회 의존)도 같이 명시적 flush로 |
| 3 | `VrStrategy.java:101-102` vs `:170,177` VR_BUY_01 | 의심(낮음, timing 다른 동일 leg) | 같은 날 공존 시나리오 확인 또는 인덱스에 timing 포함 |
| 4 | `OPEN_STATUSES`(`OrderPersistenceAdapter.java:34`) ↔ 인덱스 집합 불일치 | 의심(낮음) | FILLED·PARTIALLY_FILLED 같은 슬롯 재계획 시점 확인 |
| 5 | `TradingReporter.java:134-149` mainOrders(전 사이클) vs cancelledOrders(현재 사이클) | 미확인 | 사이클 경계의 CANCELLED→FILLED 되살림 여부 |
| 6 | 테스트: `OrderPersistenceAdapterDbTest.java:62-66`, `:333-340`, `StrategyCyclePersistenceAdapterTest.java:84-85,136-137` | 인덱스 도입 시 깨짐 | 사이클을 서로 다른 strategy에 분리하거나 한쪽 end_date 설정, crypto 테스트는 leg 분리 |
| 7 | 운영 데이터 기존 중복 | 미확인 | 5절 쿼리 |
