# account Spring Modulith 이전 설계 (4단계 — account 단독)

## 배경/목적

레거시 최상위 패키지 모듈 카탈로그 재편([[2026-08-31-legacy-module-catalog-design]])의 착수 순서 4번째 항목. market(5)→privacy(6)→stats(7)→admin(8)→user(9) 5개 CLOSED 모듈 이전이 전부 완료됐고(commit 4294c90c), 남은 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`) 잔존물은 account/strategy-config다.

**정정(2026-09-02, 실행 계획 작성 중 발견 — 이 스펙의 최초 버전을 대체)**: 원래 이 스펙은 account+strategy-config를 "서로 얽혀있어 분리 착수 어려움"이라는 전제로 함께 다뤘다. 그러나 실행 계획 작성 과정의 코드 실측 결과 이 전제가 틀렸다는 게 드러났다:

- **account↔strategy-config**: 진짜 나쁜 엣지는 `AccountService.delete()`의 `strategyPort.deleteByAccountId()` 직접 호출 1건뿐. 나머지 커플링(broker/notify/trading 포트의 `Account` 파라미터)은 강도 낮은 own-type 대상이라 account 단독으로 해소 가능.
- **strategy-config↔trading**: 실측 결과 **원자적 트랜잭션 결합**이 존재한다 — `CycleSnapshotCreator.reconfigureVrCycle()`(trading, `@Transactional`)이 strategy-config 소유 `StrategyVersionPort`(버전 소프트삭제+신규저장)와 `VrStrategyLifecycle.saveVersionDetail()`(`strategy_vr_version` 저장)을 trading 소유 `StrategyCycleVrPort`/`CyclePositionPort`와 **같은 트랜잭션**에서 호출한다(중간 실패 시 고아 상태 방지 주석 명시). 이건 own-type 치환이나 이벤트 팬아웃으로 풀 수 없는 결합이다 — 이벤트 전환은 원자성이 깨지고(강타입 재설정 요청이 도중 실패하면 버전은 새로 생겼는데 사이클은 안 도는 상태가 남을 수 있음), own-type은애초에 시그니처가 아니라 두 모듈의 쓰기가 한 트랜잭션에 걸려있는 문제라 적용 대상이 아니다. 포트 역전(trading이 필요한 만큼만 담은 좁은 포트를 정의하고 strategy-config가 구현, `ApprovalPolicyPort` 패턴)으로 풀어야 하며 별도 설계가 필요하다.
- 사용자 결정(2026-09-02): **account를 먼저 단독으로 이전**한다. strategy-config는 이 스펙의 스코프에서 제외하고, trading과의 원자적 결합을 더 깊게 검토한 뒤 별도 스펙+계획으로 다룬다.

이 문서는 **account만**의 최종 모듈 구조·커플링 해소 방식·착수 순서를 정의한다. strategy-config는 스코프 아웃(아래 "스코프 아웃" 절).

## 모듈 구조 결정

**단독 CLOSED 모듈, 리프 성격.** account는 계좌 자격증명·브로커 연결 자체로 독립적 도메인 개념이며 strategy 없이도 존재 의미가 있다. 이번 이전 후에는 broker/notify/trading/admin이 account를 leaf로 참조하는 단방향 구조가 된다(strategy-config만 예외로 남아 레거시 OPEN에 잔류 — 별도 스펙 대상).

## 결합도 실측 (2026-09-02 grep 기준)

| 위치 | 커플링 | 개수 |
|---|---|---|
| `com.kista.broker.application.port.output.*` | 시그니처가 `Account` 직접 참조 | 11개 파일(BrokerAdapterPort/StockInfoPort/BrokerOrderCorrectionPort/BrokerConnectionTestPort/SellableQuantityPort/BrokerAccountPort/ExecutionPort/LiveBalancePort/PortfolioPort/BrokerPricePort/MarginPort) — 단 KIS/Toss/Mock 어댑터 구현체가 실제로 쓰는 필드는 `id()/appKey()/secretKey()/accountNo()/brokerAccountCode()` 5개뿐, `broker()`는 어댑터 내부가 아닌 `BrokerAdapterRegistry`/`BrokerConnectionTesters`(application.service)가 라우팅 키로만 사용. `Strategy` 관련 커플링은 nested enum(`Ticker`)만 사용해 이번 스펙과 무관 — sharedkernel 이관(향후 strategy-config 스펙 대상)으로 자동 해소될 부분이라 이 스펙에서 손대지 않는다 |
| `com.kista.notify.application.port.output.*` | `UserNotificationPort`(13메서드)/`NotifyPort`(4메서드)가 `Account` 직접 참조 | 2개 파일 — 구현체 실사용은 `account.nickname()` 한 필드뿐(FcmAdapter/TelegramUserNotificationAdapter/TradingReportNotifier) |
| `com.kista.trading.*` | `TradingExecutionUseCase.execute(Strategy, Account, User)` 1곳이 실제 포트 시그니처. `BatchContext`(trading domain record)가 `Account account` 필드 보유, 이를 경유해 여러 서비스가 `ctx.account()`로 파생 사용 | trading→account는 **일방향 리프**(privacy 패턴과 동일) — account 모듈이 `com.kista.trading`을 참조하는 곳은 0건(재배치 예정인 통계 서비스 3종이 유일한 연결고리, 아래 재배치 항목 참고). own-type 불필요, `Account` 그대로 파라미터로 둬도 순환 아님 |
| `com.kista.admin.*` | `AccountService.requireBrokerEnabled()`가 `RuntimeSettingsPort.load().brokers()` 직접 소비(forward) ↔ `AdminAccountResponse`/`AdminSelectionChain`/`RuntimeSettings`가 `Account`/`Account.Broker` 직접 참조(backward) | **신규 발견 순환**(이 스펙의 이전 버전에 없던 항목) — admin↔user의 `ApprovalPolicyPort` 포트 역전과 동일 패턴으로 해소 가능 |
| `AccountService` cascade | `strategyPort.deleteByAccountId(accountId)` 직접 호출 — account→strategy-config 부당한 역방향 | 1곳 |
| `UserCascadeDeleter`(user, 이미 CLOSED) | `accountPort.deleteByUserId(userId)` 직접 호출 | account 쪽에서 `com.kista.user`를 참조하는 곳이 0건(grep 확인)이라 **순환 아님** — user→account leaf 참조로 그대로 유지, 이벤트 전환 불필요(스펙 이전 버전이 "user↔strategy-config·user↔account 둘 다 처리 필요"로 적었던 것 중 account 몫은 실측 결과 처리 불필요로 정정) |
| 통계 서비스 3종 | `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`가 `com.kista.application.service.account` 패키지에 있으나 실질 stats 관심사(broker/trading/privacy/strategy 의존) | account 재배치가 아니라 stats 재배치 대상 — 이걸 옮기면 account가 trading/broker/privacy/strategy를 참조하던 유일한 연결고리가 사라짐 |

## 해소 방식

기존 own-type/포트역전 선례(broker `Direction`/`OrderType`/`BrokerBalance` 등, admin↔user `ApprovalPolicyPort`)를 그대로 재사용한다. 새 패턴 없음.

1. **broker 11개 포트 own-type 전환**: `Account` 전체 레코드 대신 broker가 실제로 쓰는 5개 필드(`id/appKey/secretKey/accountNo/brokerAccountCode`)만 담은 `com.kista.broker.domain.model.BrokerAccountRef`(가칭) 신설. account 쪽(정확히는 이를 호출하는 trading/admin 서비스)이 매핑 책임. `broker()` 라우팅은 `BrokerAdapterRegistry`/`BrokerConnectionTesters`가 여전히 `Account.Broker`(sharedkernel 이관 여부는 이 스펙 범위 밖 — 그대로 `Account` nested enum 유지해도 account가 CLOSED 모듈로서 이 enum을 "domain" NamedInterface로 공개하면 문제없음)를 쓰므로 별도 처리.
2. **notify 2개 포트 own-type 전환**: `Account` 의존을 `String accountNickname` 단일 필드로 축소(구현체가 `nickname()`만 쓰므로 record 신설도 불필요할 만큼 작음 — 계획 단계에서 파라미터 타입만 `String`으로 바꾸는 것으로 충분한지, 아니면 최소 record가 나은지 확정).
3. **trading `TradingExecutionUseCase.execute`**: `Account` own-type 전환 불필요 — trading→account가 이미 일방향 리프이므로 시그니처 그대로 유지.
4. **admin↔account 포트 역전**: account가 `BrokerEnabledPort`(가칭, 1메서드: `boolean enabled(Account.Broker broker)`)를 자체 정의하고 admin의 `RuntimeSettingsService`가 구현(`ApprovalPolicyPort`와 동일 패턴). `AdminAccountResponse`/`AdminSelectionChain`/`RuntimeSettings`의 `Account`/`Account.Broker` 참조는 admin→account 정상 forward이므로 그대로 유지(account가 "domain" NamedInterface로 공개하면 문제없음).
5. **cascade 이벤트 전환**: `AccountService`의 `strategyPort.deleteByAccountId()` 직접 호출을 `AccountDeletedEvent` 발행(`com.kista.account.application.event`, EPR 추적)으로 전환. strategy-config(레거시 잔류)가 구독해 자기 행을 소프트삭제 — `UserDeletedEvent` 선례와 동일하나, strategy-config가 아직 모듈이 아니므로 리스너는 레거시 `com.kista.application.service.strategy` 패키지에 둔다(strategy-config 이전 시 함께 옮김).

## 착수 순서

admin/privacy/user 선례(사전 grep 해소 → 물리 이전 → `@ApplicationModule` 선언 + `verify()` 게이트)를 그대로 따른다. account는 리프라 전이 순환 가능성이 낮지만, 과거 5개 모듈 전부 "안전해 보이는 리프도 물리 이전 후 verify()에서 걸린 사례"(market의 `market→notify→trading→market` 등)가 있었으므로 게이트는 생략하지 않는다.

1. own-type 전환 2건(broker/notify) + 포트역전 1건(admin↔account) + cascade 이벤트 1건 — 코드 위치는 그대로 두고 커플링만 해소. 각 태스크 완료마다 기존 단위테스트 통과 확인.
2. 통계 서비스 3종(`AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`) → `com.kista.stats.application.service`로 재배치(관련 UseCase 인터페이스 2개도 동반 이전).
3. account 물리 이전(`domain/model` → `application/{usecase,port/output,event}` → `adapter/{in,out}` 순) → `@ApplicationModule` CLOSED 선언 → `verify()` 실행 — 신규 순환 발견 시 즉시 멈추고 보고, 별도 해소 태스크 추가.

## 테스트/검증

- own-type/포트역전 전환마다 시그니처만 바뀌므로 기존 단위테스트 그대로 통과해야 함 — 실패 시 매핑 로직 버그.
- 물리 이전 후 `./gradlew test --tests 'com.kista.architecture.*'` 필수(ArchUnit `HexagonalArchitectureTest` + `ModulithArchitectureTest`).
- 전체 `./gradlew test`는 물리 이전 완료 후 최종 1회.
- Flyway 변경 없음(테이블 재배치 아닌 패키지 재배치) — `AccountDeletedEvent` 신설로 EPR(`event_publication`) 테이블에 새 이벤트 타입 행 추가되는 점만 인지.

## 스코프 아웃

- **strategy-config 이전 전체** — trading과의 원자적 트랜잭션 결합(`CycleSnapshotCreator.reconfigureVrCycle`이 strategy-config `StrategyVersionPort`+`VrStrategyLifecycle`와 trading `StrategyCycleVrPort`/`CyclePositionPort`를 한 트랜잭션에서 호출)을 별도로 더 깊이 조사한 뒤 독립 스펙으로 진행. `VrStrategyLifecycle` 재배치 여부(trading 소유로 통째 이전 불가 — `StrategyVrDetailPort`는 strategy-config 데이터라 분리 또는 포트 역전 필요)도 그 스펙에서 확정.
- Strategy nested enum(`Ticker`/`Type`/`Status`/`CycleSeedType`) sharedkernel 이관 — strategy-config 스펙 대상, 이번 account 이전과 무관.
- `Account.Broker` sharedkernel 이관 — account가 CLOSED 모듈로서 직접 소유·공개하면 되므로 이번 스펙에서는 불필요. 향후 다른 모듈에서 `Account` 전체를 안 쓰고 `Broker`만 필요로 하는 사례가 늘면 재검토.
- Account 도메인 모델 필드 변경, 신규 기능 추가 — 이 이전은 순수 패키지 재배치+커플링 해소.

## 다음 단계

이 스펙 승인 후 `writing-plans`로 account 단독 실행 계획 작성.
