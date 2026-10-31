# 매매 배치 재기동 재개(resume) 설계

- 작성일: 2026-10-02
- 트랙: 배포 파이프라인 후속 1번 (6번 완료 후, 3번 GitOps reconcile 재편의 선행 조건)
- 목표: `kista-trading`이 매매 배치 도중 graceful 재기동돼도 그날 매매가 유실·중복되지 않게 해, 배포 매매 가드(`deploy-trading`의 KST 시각 창 + `trading.scheduler_locks` 조회)를 제거 가능한 상태로 만든다.

## 1. 합의 사항

- **성공 기준**: graceful 재기동(배포)은 무인으로 안전해야 한다. SIGKILL·OOM은 드문 사고로 보고 관리자 알림 + 수동 대응을 허용한다.
- **가드 제거 위치**: 이 작업은 코드만 바꾼다. 가드 파일(`_deploy-role.yml` 가드 스텝, `apply_trading_guard`, `.github/scripts/trading-guard.sh`, `deploy/server/bin/trading-locks.sh`, 관련 bats, kista-infra trading-guard/trading-locks)은 3번 트랙이 배포를 재작성할 때 제거한다.
- **매매 공식 불변**: `docs/agents/modules/trading-formulas.md`는 손대지 않는다.

## 2. 현황 분석 (설계 근거)

### 2.1 마감 배치 단계별 재실행 안전성

`TradingService.executeBatch`는 다음 순서로 진행된다. 계획 → `orderAt` 대기 → `placeAll` → `postClose` 대기 → `reportAll`.

| 단계 | 재실행 안전? | 근거 |
|---|---|---|
| 계획 → `orderAt` 대기 | 안전 | PLANNED만 존재하고 증권사에는 아무것도 접수되지 않았다. slot 점유로 멱등하다 |
| `placeEach` | 위험 | `place()`와 `markPlaced` 사이에 사망하면 PLANNED가 남아 재실행 때 이중 접수된다. VT 소켓 인터럽트 시에는 브로커가 접수했는데 FAILED로 기록된다 |
| `postClose` 대기 | 재계획 시 위험 | slot 점유는 PLANNED/PLACED만 센다(`findPlannedOrPlacedByCycleAndDate`). 재계획하면 FAILED leg가 재생성·즉시 접수된다 |
| `recordAndNotify` | 위험 | `cycle_position`이 append된다(trade_date·dedup 없음). `CycleCompletedEvent`와 리포트가 재발행된다. 리포트 후 재계획하면 FILLED/CANCELLED slot이 비어 주문이 재생성된다 |

개장 배치(`placeOpenOrders`)는 대기 **이후**에 계획·접수하므로, 대기 중 중단이면 통째로 재실행해도 안전하다.

### 2.2 현재 종료 시 배치 스레드는 인터럽트되지 않는다

- Boot 4는 VT가 활성이면 `@Scheduled` 실행기로 `SimpleAsyncTaskScheduler`를 쓴다.
- `spring.task.scheduling.shutdown.await-termination` 미설정 시 `taskTerminationTimeout=0`이다. 이 경우 `SimpleAsyncTaskExecutor`는 실행 중 스레드를 추적하지 않아 `close()`가 인터럽트를 보내지 않는다.
- 배치 VT(데몬)는 JVM 종료와 함께 사라진다.
- 결과:
  - 기존 인터럽트 대응 코드(`waitFor`의 `TradingErrorEvent`, `notifyBatchInterrupted`, `SchedulerJobRunner` FAILED, `SchedulerLockService.release`)는 운영 배포 경로에서 사실상 실행되지 않는다.
  - 락은 `finished_at IS NULL`인 채 TTL까지 남는다.
  - graceful 종료와 SIGKILL이 같은 흔적을 남긴다.

### 2.3 사전 잔고는 DB에서 결정적으로 재구성된다

- `TradingBalanceLoader.loadBalanceOrThrow`는 브로커 라이브 잔고가 아니라 최신 `cycle_position`(전략별 가상 원장)을 읽는다.
- 따라서 해당 사이클의 당일 리포트가 아직 저장되지 않았다면, 재조회해도 계획 시점과 같은 사전 잔고를 얻는다. 스냅샷을 따로 영속화할 필요가 없다.

### 2.4 배포는 이전 컨테이너 종료 후 새 컨테이너 기동

`deploy/server/bin/deploy-role.sh`가 `docker compose up -d --no-deps`로 컨테이너를 재생성한다. 이전 컨테이너를 정지(`stop_grace_period: 200s`)한 뒤 새 컨테이너를 기동하므로 두 인스턴스가 겹치지 않는다.

## 3. 불변 전제 (3번 트랙에 전달)

`kista-trading`은 **단일 인스턴스**이며, 배포는 **이전 인스턴스 종료 후 새 인스턴스 기동**(겹침 없음) 순서를 따른다. 기동 시 재개 경로가 잔여 스케쥴러 락을 무조건 인수하는 것이 이 전제에 의존한다. 3번 트랙이 kista-trading에 겹침 배포(blue/green·start-first)를 도입하면 이 설계는 깨진다.

## 4. 구성 요소와 상태 모델

### 4.1 신규 테이블 (trading-core Flyway `V4`)

`trading.trading_batch_run`: (job, 거래일) 단계 체크포인트.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| job_name | VARCHAR(50) | PK. `trading-close` / `trading-open` |
| trade_date | DATE | PK. KST 거래일 |
| phase | VARCHAR(20) | NOT NULL |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT now() |

- `trading-close` 단계: `PLANNED`(PLANNED 저장 완료·접수 대기) → `PLACING`(접수 진입) → `PLACED`(접수 완료·마감 대기) → `DONE`(리포트 완료).
- `trading-open` 단계: `PLACING` → `DONE`.
- 조기 반환(휴장, 대상 0건, 시작예정일 미도래 전량 제외, 계산 결과 0건)도 `DONE`으로 기록한다. 이렇게 해야 "행 없음 = 아직 실행 안 됨"이 성립한다.
- `scheduler_locks`는 범용 플랫폼 테이블로 유지한다. 매매 고유 단계는 trading이 소유한다.

`trading.trading_batch_report`: 전략별 당일 리포트 완료 마커.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| trade_date | DATE | PK |
| strategy_id | UUID | PK (FK 없음 — soft delete 테이블 참조 회피) |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT now() |

- 키를 사이클이 아니라 **전략**으로 잡는다. 리포트가 청산을 감지하면 `CycleRotationService`가 새 사이클을 만든다. 사이클 키로 잡으면 재개 시 다시 빌드한 context가 새 사이클을 가리켜 마커를 놓친다.
- `CyclePositionPersistor`가 `cycle_position`(+ INFINITE 상세) 저장 **직후**에 insert한다.
  - 같은 트랜잭션으로 묶지 않는다. 현재 저장 경로에 트랜잭션이 없고, 뒤따르는 rotation까지 트랜잭션에 넣으면 범위가 커진다.
  - 저장과 마커 사이(수 ms)에 SIGKILL이 걸리면 재개 시 position이 중복될 수 있다. graceful 종료는 CRITICAL 대기로 이 창을 밟지 않으므로 허용한다.
- 재개 시 마커가 있는 전략은 리포트를 건너뛴다. 그래서 `cycle_position` 중복 append, `CycleCompletedEvent`·리포트 재발행이 생기지 않는다.

### 4.2 컴포넌트 (trading 모듈)

- `TradingBatchRunState`: 실행 중인 배치 스레드와 현재 구간(`SLEEPING` / `CRITICAL`)을 등록하는 holder. 함께 종료 요청 플래그(`stopping`)를 보관한다.
- `TradingBatchShutdownCoordinator`: `SmartLifecycle`이며 종료 흐름(5장)을 담당한다.
- `TradingBatchResumer`: `ApplicationReadyEvent` 시점에 재개를 판정·실행한다(6장).
- `TradingBatchRunPort`: `application/port/output` 위치. JdbcTemplate 기반 persistence 어댑터가 구현하며 체크포인트를 upsert·조회하고 마커를 insert·조회한다.

### 4.3 기존 코드 변경

- `TradingService.executeBatch`·`placeOpenOrders`:
  - 단계 전이를 기록한다.
  - `waitFor`는 SLEEPING 구간을, 접수·리포트 블록은 CRITICAL 구간을 표시한다.
  - 리포트만 재개하는 진입점(`resumeCloseReport`)을 추가한다.
- `TradingCloseScheduler` / `TradingOpenScheduler`: 재개 진입점을 추가한다. 락을 인수한 뒤 `SchedulerJobRunner`로 실행한다.
- `CyclePositionPersistor`: 마커 insert를 추가한다.
- `SchedulerLockService`(shared):
  - `takeOver(lockName, ttl, task)`를 추가한다. 다른 owner가 쥔 락이면 만료 전이어도 인수한다. 단 **자기 프로세스가 쥔 락은 인수하지 않는다**(`lock_until <= now() OR locked_by <> me`). 기동 직후 cron이 먼저 발화해 이미 실행 중인 경우의 이중 실행을 막기 위해서다.
  - 실행 결과 처리(markFinished/release)는 `tryRun`과 같다.
  - `ownerId`에 무작위 UUID를 덧붙인다. `docker restart`처럼 hostname·pid가 그대로인 재기동에서도 이전 프로세스와 owner가 달라야 위 조건이 성립한다.
- `DstInfo`:
  - `marketCloseAt()`을 추가한다(마감 접수 마감 계산용).
  - `forOpenBatch(tradeDate)`를 추가한다. 거래일 T의 개장 시각을 T-1일 저녁으로 산출한다. 기존 `calculate()`는 `marketOpen`을 "오늘 날짜"로 고정하므로, 자정 이후 개장 배치를 재개하면 그날 밤 개장까지 약 22시간을 잘못 대기한다.
  - `TradingService.placeOpenOrders(contexts)`는 `forOpenBatch(nextTradeDate())`를 쓴다.
  - 테스트 주입용 `calculate(ZonedDateTime)`을 public으로 연다.
- `TradingOpenScheduler` PRIVACY 장전 가드의 조회일을 `LocalDate.now()`에서 `DstInfo.nextTradeDate()`로 바꾼다. `findTodayTrade`는 KST 거래일을 받는 계약이며, 자정 이후 재개 때 날짜가 어긋나지 않게 하기 위해서다.
- `application-prod.yml`은 바꾸지 않는다.
  - 코디네이터 `stop()`은 동기 블로킹이라 `timeout-per-shutdown-phase`(비동기 콜백 대기 상한)의 영향을 받지 않는다.
  - 대신 CRITICAL 대기 상한을 150s로 둔다. 웹서버 graceful 30s와 합쳐 compose `stop_grace_period` 200s 안에 들어간다.

## 5. 종료 흐름 (협조적 shutdown)

SIGTERM이 오면 Spring context가 닫히면서 `SmartLifecycle.stop()`이 phase 내림차순으로 호출된다. 코디네이터 phase는 `Integer.MAX_VALUE - 1`로 둬 웹서버 graceful보다 먼저 멈춘다.

| 배치 상태 | 동작 |
|---|---|
| 없음 | 즉시 반환 |
| SLEEPING | `stopping=true`로 표시하고 배치 스레드를 `interrupt()`한다 |
| CRITICAL | `stopping=true`로 표시하고 구간 종료까지 대기한다(상한 `trading.shutdown.critical-wait`, 기본 150s). 구간이 끝나면 체크포인트가 이미 다음 단계로 기록돼 있고, 스레드는 다음 `waitFor`에서 즉시 종료 경로로 빠진다 |
| CRITICAL 상한 초과 | 관리자 알림 "[재기동] 접수/리포트 진행 중 강제 종료 — 수동 확인 필요" 후 반환한다. JVM 종료로 스레드가 사라지며, SIGKILL과 같은 상태가 된다 |

- `waitFor` 인터럽트 처리:
  - `stopping`이면 사용자 대상 `notifyBatchInterrupted`를 억제한다.
  - 관리자 알림 1건만 보낸다. "[재기동] {label} 대기 중 종료 — 기동 후 재개 예정".
  - 이후 rethrow한다.
  - `stopping`이 이미 서 있는 상태에서 `waitFor`에 진입하면 sleep 없이 같은 경로로 빠진다.
- `SchedulerJobRunner` FAILED 이벤트와 `SchedulerLockService.release()`는 기존대로 동작한다. 재개는 락이 아니라 체크포인트로 판정하므로 이 동작은 무해하다.
- CRITICAL 범위:
  - 마감 경로: `placeAll` 전체(가격 재조회 포함), `reportAll` 전체.
  - 개장 경로: 계획·예산 배정·저장·AT_OPEN 접수 전체.
- Boot `await-termination`은 켜지 않는다. 켜면 대기 구간까지 기다리게 된다.

## 6. 기동 시 재개 판정

`ApplicationReadyEvent`를 받으면 `TradingBatchResumer`가 VT 1개에서 실행된다. 기동을 막지 않는다. `scheduler.enabled=false`면 빈이 비활성이다.

### 6.1 재개 대상 job

| job | tradeDate | 판정 창 (KST) |
|---|---|---|
| `trading-close` | 오늘 | 화~토, 04:30 ≤ now < 당일 22:30 |
| `trading-open` | `DstInfo.nextTradeDate()` | 월~금 22:30 ≤ now < 다음날 04:30 |

### 6.2 `trading-close`

접수 마감은 `marketClose − 10분`이다(설정값 `trading.resume.close-placement-cutoff`).

| phase | 접수 마감 전 | 접수 마감 후 |
|---|---|---|
| 행 없음 / `PLANNED` | `executeBatch` 전체 재실행. slot 멱등으로 재계획하고, 남은 시간만 대기한 뒤 접수·리포트한다 | 관리자 알림 "마감 매매 미접수 — 수동 확인", 실행 안 함 |
| `PLACING` | 관리자 경고 "접수 도중 중단 — 이중 접수 여부 확인" 후 `executeBatch` 전체 재실행. 남은 PLANNED만 접수된다 | 관리자 경고, 실행 안 함 |
| `PLACED` | 리포트만 재개: 남은 postClose까지 대기한 뒤 마커 없는 전략만 리포트한다 | 같음 |
| `DONE` | 없음 | 없음 |

리포트만 재개하는 경로가 쓰는 값:
- contexts: `contextFactory.buildAll(strategyPort.findAllActive())`로 다시 빌드한다.
- 사전 잔고: 최신 `cycle_position`(2.3).
- mainOrders: DB에서 사이클·거래일 기준 PLACED 주문을 가져온다.
- 확정 종가와 privacyBase: 재조회한다.
- 마지막 사이클까지 끝나면 `DONE`으로 기록한다.

### 6.3 `trading-open`

| phase | 동작 |
|---|---|
| 행 없음 / `PLACING` | `placeOpenOrders` 전체 재실행. 남은 개장 대기만 기다리고, 이미 개장했으면 즉시 계획·접수한다. AT_OPEN slot 멱등이다. `PLACING`이면 관리자 경고를 먼저 보낸다 |
| `DONE` | 없음 |

### 6.4 락과 알림

- 재개 경로는 `SchedulerLockService.takeOver`로 잔여 락을 인수한다(3장 전제).
- 인수 이후의 cron·수동 `runNow`는 기존대로 락 획득에 실패해 skip된다.
- 재개 시작·결과는 관리자에게 1건씩 알린다. 예: "[재개] trading-close PLACED → 리포트 재개".

## 7. EPR 알림 중복

- Modulith EPR 재발행 대상은 리스너가 완료되지 않은 publication뿐이다(at-least-once).
- 중복은 "발송 직후·완료 마킹 전 사망"한 극소수 건으로 한정되므로 별도 대응 없이 문서화만 한다.
- 실질적인 중복 원인이던 배치 재실행은 전략별 마커로 막는다. 재개 경로에서 새로 발행되는 리포트·`CycleCompletedEvent`는 전략·거래일당 1회가 보장된다.

## 8. 테스트

- `TradingBatchShutdownCoordinatorTest`:
  - SLEEPING이면 즉시 인터럽트된다.
  - CRITICAL이면 구간 종료까지 대기한다.
  - 상한을 넘기면 알림 후 반환한다.
  - `stopping` 상태에서 `waitFor`에 진입하면 즉시 예외가 난다.
- `TradingBatchResumerTest`: 6장 표의 각 셀(job × phase × 시각)을 고정 시각으로 검증한다.
- `TradingServiceTest` 보강:
  - 단계 전이 기록(조기 반환 시 `DONE` 포함)을 검증한다.
  - `resumeCloseReport`가 마커 있는 사이클을 건너뛰는지 검증한다.
  - 종료 인터럽트 시 사용자 알림이 억제되는지 검증한다.
- persistence(`DataJpaTestBase`):
  - `trading_batch_run` upsert를 검증한다.
  - `trading_batch_report` 마커 insert·조회를 검증한다.
- `SchedulerLockService.takeOver` 테스트.
- 로컬 재현 1회:
  1. trading-core를 짧은 대기로 기동한다.
  2. SIGTERM을 보내고 재기동한다.
  3. 재개 로그를 확인하고, 주문 중복이 0건인지 확인한다.

## 9. 문서 갱신

- `docs/agents/workflow.md`:
  - 종료·재개 흐름을 추가한다.
  - "`waitFor()` 인터럽트" 항목을 갱신한다.
  - "병렬 접수 인터럽트 리스크" 항목을 갱신한다.
- `docs/agents/constraints.md` Git 규칙: 가드 서술에 "1번 완료로 제거 가능, 실제 제거는 3번 트랙"과 3장 전제를 명시한다.
- `docs/agents/modules/trading.md`: 신규 컴포넌트·테이블을 추가한다.

## 10. 범위 밖

- 가드 파일 제거: 3번 트랙에서 한다.
- 브로커 주문 조회로 torn-order를 자동 대사하는 작업.
- SIGKILL·OOM 무인 복구.
- 운영 배포: 이번 변경은 아직 가드를 타므로 매매 시간대를 피해서 배포한다.

## 11. 완료 보고

다음 내용을 `kista-deploy-3env`와 `kista-api-8f`에 보낸다.
- 머지 SHA.
- "가드 제거 가능".
- 3장 전제(단일 인스턴스·비겹침 배포).
