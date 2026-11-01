# trading-core 코드 품질 원장

trading-core 감사 결과를 모은 원장이다. 한 번에 리팩토링하지 않는다. 다른 정해진 작업이 그 파일이나 패키지를 건드릴 때 해당 항목을 같이 해소한다.

## 운영 규칙

- 작업이 아래 "위치"에 적힌 파일이나 패키지를 건드리면 그 항목도 같은 작업에서 처리한다. 범위가 크면 착수 전에 사용자에게 알린다.
- 처리하면 "상태"를 `done(<커밋 해시>)`로 바꾸고, 그 항목의 "가드"도 함께 넣는다.
- 작업 중 새로 발견한 비일관성·중복은 같은 형식으로 추가한다. 근거는 grep으로 확인한 file:line만 적는다(`constraints.md` "증거 의무"). 추정은 "미확인"이라고 명시한다.
- 추출 기준은 CLAUDE.md "코드 철학"을 따른다. 같은 로직이 3곳 이상이고 같은 이유로 함께 바뀌면 추출한다. 2곳이거나 바뀌는 이유가 다르면 "유지 목록"에 이유를 남겨 재제안하지 않는다.
- 매매 공식(`modules/tradingcore/trading-formulas.md`)에 닿는 항목은 위험을 "높음"으로 둔다. 공식 결과를 바꾸지 않는다는 테스트를 먼저 고정한 뒤 손댄다.
- 재논의 금지: 메모리 `project_review_refactor_2026_10_31`, `project_2026_07_11_full_review`, `own-type-ledger.md`, `constraints.md`의 확정 판단. 감사 중 그와 겹친 발견은 "유지 목록"에 출처를 적었다.
- ID는 고정 식별자다. 문서 안 순서(효과 대 위험)와 번호는 일치하지 않을 수 있다.
- 줄 번호는 감사 시점 HEAD `0b761982d`(2026-11-01) 기준이다. 착수 시 다시 grep한다. ✔는 메인 세션이 직접 재확인한 위치다.

## 1차 감사 범위와 방법 (2026-11-01)

- 대상은 `trading-core/src/main/java` 605파일이다. 테스트는 제외했다.
- 주제 10개를 주제별로 trading-core 전체에 가로 grep했다. 브로커 실패 처리, null·기본값, 입력 검증·예외 문구, 매핑, 날짜·시간대, 금액·수량 스케일, 이벤트·관리자 알림, 로깅 형식, 전략 타입 분기, 소유권 검증. 여기에 모듈 간·브로커 어댑터 간 중복과 서비스 안의 도메인 규칙을 더했다.
- 탐색은 저비용 서브에이전트 4개가 맡았다. 기준 판정과 원장 작성은 메인 세션이 했다.

## 의심 (확인 필요, 코드 수정 전 먼저 확인)

### CQ-00 PRIVACY 상세의 currentRound가 항상 0.0
- 주제: 매핑·기본값. 유형: 비일관성(버그 의심).
- 위치:
  - `trading/application/service/StrategyDetailAssembler.java:168-172` ✔. 주석은 "INFINITE·PRIVACY만 계산"인데, `divisionCount == null ? 0 : divisionCount`로 넘긴다.
  - `StrategyDetailAssembler.java:46-48,124-127` ✔. divisionCount는 `strategy.isInfinite()`일 때만 채워지므로 PRIVACY는 항상 null이다.
  - `matching/domain/model/InfinitePosition.java:60` ✔. `if (divisionCount == 0 ...) return 0.0`.
- 근거: PRIVACY 전략은 위 경로로 currentRound가 무조건 0.0이 된다. 다른 경로의 기본값은 `StrategyDefaults.DEFAULT_DIVISION_COUNT = 20`이다(`shared/.../StrategyDefaults.java:8`, `CyclePositionPersistor.java:86` 등의 `.orElse(DEFAULT_DIVISION_COUNT)`).
- 확인할 것: PRIVACY 상세 화면에 회차 표시가 의도된 것인지 여부. 의도라면 주석과 계산식 중 무엇이 맞는가.
- 예상 효과: 표시값 정정, 또는 주석 정정.
- 위험: 높음. `currentRound` 공식(`trading-formulas.md`)에 닿고, 사용자 화면 값이 바뀐다.
- 가드: 결론이 나면 `StrategyDetailAssembler` 단위 테스트에 PRIVACY currentRound 단언을 추가한다.
- 상태: open.

## 항목 (효과 대 위험 순, 가드를 먼저 넣으면 재발이 멈추는 항목이 위)

### CQ-01 America/New_York ZoneId 상수 4벌
- 주제: 날짜·시간대. 유형: 중복·비일관성.
- 위치 ✔:
  - `broker/adapter/out/toss/TossPriceApi.java:166`
  - `marketcalendar/domain/model/MarketSessionSnapshot.java:16`
  - `trading/domain/model/DstInfo.java:15`
  - `stats/adapter/out/alpaca/AlpacaCandleAdapter.java:30` (이름은 `NEW_YORK`)
- 기준 패턴: `shared/platform/time/TimeZones.KST`. 그 클래스 주석이 "`ZoneId.of("Asia/Seoul")` 인라인 반복 금지"다. `TimeZones.NY`를 추가해 4곳이 참조하게 한다.
- 근거: `grep 'ZoneId.of("America/New_York")'` 결과 4건/4파일. `TimeZones`에는 KST만 있다.
- 예상 효과: 시간대 상수의 SSOT를 일원화하고, 신규 코드에서 같은 인라인이 반복되지 않게 막는다.
- 위험: 낮음. 상수만 치환하고 값은 같다.
- 가드(ArchUnit 초안, 루트 `HexagonalArchitectureTest`에 `usTradeDates_must_only_be_used_by_allowlisted_adapters`와 나란히 둔다): `noClasses().that().doNotHaveFullyQualifiedName("com.kista.shared.platform.time.TimeZones").should().callMethod(ZoneId.class, "of", String.class)`.
- 상태: open.

### CQ-02 application 서비스가 TradingErrorReportPort를 직접 호출
- 주제: 이벤트·관리자 알림. 유형: 비일관성.
- 위치 ✔ (`TradingErrorReportPort`를 참조하는 application.service):
  - `trading/application/service/PrivacyBaseGuard.java:29,34`
  - `UserTradingDataEraseService.java:52`
  - `CryptoDcaRunner.java:104`
- 기준 패턴: application 서비스는 `eventPublisher.publishEvent(new TradingErrorEvent(...))`를 쓴다. 29건/13파일. `TradingErrorReportPort`는 adapter.in(스케쥴러)이 application.event를 직접 참조하지 않게 하려고 둔 우회 경로다(`support/TradingErrorReporter.java:8` 주석 ✔). 구현이 결국 `new TradingErrorEvent(null, e.getMessage())`를 발행하므로 도착지는 같다.
- 근거: 같은 계층에서 관리자 알림 방식이 두 가지로 갈린다. 포트 경로는 `Exception`만 받아 메시지만 넘긴다. 그래서 userId 같은 문맥을 실을 수 없다.
- 예상 효과: application 계층 알림 방식을 하나로 통일한다.
- 위험: 낮음. 도착지와 메시지가 같다.
- 가드(ArchUnit 초안): `noClasses().that().resideInAPackage("..application.service..").and().doNotHaveSimpleName("TradingErrorReporter").should().dependOnClassesThat().haveSimpleName("TradingErrorReportPort")`.
- 상태: open.

### CQ-03 전략 타입 술어로 capability를 우회하는 분기
- 주제: 전략 타입별 분기. 유형: 비일관성·OOP(capability 대체).
- 위치:
  - `isPrivacy() ? privacyBase : null` ✔
    - `trading/application/service/TradingService.java:189`
    - `TradingCandidatePlanner.java:175,225`
    - `StrategyOrderPlanBuilder.java:71`. 같은 로직을 인라인했다는 주석이 있다.
  - `TradingPriceFetcher.java:80` ✔ `anyMatch(c -> c.strategy().isPrivacy())`. 기준표 조회 여부를 판정한다.
  - `CryptoDcaRunner.java:58` ✔ `s.type() == StrategyType.DCA`. `s`는 `Strategy`(`strategyPort.findAllActive()`)이므로 `Strategy.isDca()`를 우회한다.
- 기준 패턴: `CycleOrderStrategy.requiresPrivacyBase()`(`matching/domain/strategy/CycleOrderStrategy.java:35` ✔)가 `StrategyCapability.requiresPrivacyBase`를 노출한다. 타입 질의는 `Strategy.isX()`(`Strategy.java:36-49`)로 한다.
- 근거: "PRIVACY면 기준표 필요"라는 같은 규칙이 capability와 서비스 인라인 4곳에 이중으로 표현돼 있다. 기준표가 필요한 전략 타입이 추가되면 인라인 4곳이 누락된다.
- 예상 효과: 기준표 요구 판정을 capability로 일원화한다.
- 위험: 중간. 배치·프리뷰의 주문 계획 입력이 바뀐다. 결과는 같아야 한다.
- 가드(`constraints.md` 규칙 문장 초안): "전략 타입별 동작 차이는 `CycleOrderStrategy`/`StrategyCapability` capability로 묻는다. 서비스에서 `isPrivacy()`·`isVr()` 등으로 동작 입력을 고르는 분기를 새로 만들지 않는다. 표시·필터·타입 가드 예외만 허용한다. 타입 질의는 `type() == StrategyType.X` 대신 `Strategy.isX()`를 쓴다."
- 상태: open.

### CQ-04 KST 일자 → Instant 조회 상한 변환 4곳 반복
- 주제: 날짜·시간대. 유형: 중복.
- 위치 ✔:
  - `trading/application/service/support/StrategyHistoryQueryService.java:119-126`(`resolveHistoryFrom/To`)
  - `stats/application/service/AccountStatisticsService.java:125-132`(`resolveFrom/To`). 위와 문자 그대로 같다.
  - `stats/application/service/TradingStatsService.java:133`
  - `stats/application/service/InvestmentPointsQueryService.java:74`
- 기준 패턴: `atStartOfDay(TimeZones.KST)` 관용구(`constraints.md` "시간 기준 정책"). 반개구간 상한 `to.plusDays(1).atStartOfDay(KST).toInstant()`를 `shared/platform/time` 헬퍼 하나로 둔다. 예: `KstDays.endExclusive(LocalDate)`.
- 근거: `grep 'plusDays(1).atStartOfDay(TimeZones.KST)'` 결과 4건/4파일. 셋 다 to가 null이면 `LocalDate.now(TimeZones.KST)`로 채운다.
- 예상 효과: 조회 범위 경계 규칙을 일원화한다.
- 위험: 낮음.
- 가드(`constraints.md` "시간 기준 정책"에 추가할 문장 초안): "KST 일자 구간을 Instant로 바꿀 때는 `<헬퍼>`를 쓴다. `plusDays(1).atStartOfDay(...)`를 인라인하지 않는다."
- 상태: open.

### CQ-05 사용자 경로 예외 문구 형식 미준수
- 주제: 입력 검증·예외 문구. 유형: 비일관성.
- 위치: 188건이라 전수 목록 대신 재현 명령을 적는다. `grep -rnE 'new (IllegalArgumentException|IllegalStateException|NoSuchElementException|SecurityException)\("' trading-core/src/main/java | grep -v '/broker/adapter/out/\|/stats/adapter/out/'`. 결과에서 리터럴이 `."`로 끝나지 않는 줄이 대상이다. 아래는 대표 위치다.
  - 마침표가 없는 대표:
    - `stats/application/service/BacktestService.java:93-160`
    - `trading/application/service/VrReconfigureService.java:129-157`
    - `trading/domain/creation/VrRampValidator.java:26-79`
    - `VrRegistration.java:28,41`
    - `matching/domain/model/BootstrapPosition.java:19-35`
    - `DcaRule.java:38-74`
    - `ReorderService.java:73-108,168,213,220`
    - `ManualTradeCorrectionService.java:65-141`
    - `StrategyService.java:170,194,201`
    - `StrategyCreationService.java:79,138`
    - `AccountService.java:60,63,69`
  - 내부값(enum 원문·id·변수명)이 노출되는 대표:
    - `StrategyCreationService.java:52,170`
    - `ReorderService.java:103`
    - `VrReconfigureService.java:60,140,148`
    - `DcaConfigService.java:34`
    - `DcaExecutionQueryService.java:39`
    - `DcaRule.java:44-52`(`executionHour는` 등)
    - `DcaSizing.java:15-21`
    - `ManualTradeCorrectionService.java:141`
  - 영어:
    - `matching/domain/model/PlannedOrder.java:58`
    - `trading/domain/model/Order.java:114`
  - 내부 무결성 ISE(`"... 없음: strategyId=" + id`):
    - `CycleOrderComputer.java:90,92,96`
    - `StrategyDetailAssembler.java:42,119`
    - `support/TradingBalanceLoader.java:36`
- 기준 패턴: `constraints.md` "ProblemDetail 응답 계약". detail은 한국어 완전문장 "~습니다."이고 마침표를 찍으며 내부 정보를 싣지 않는다. 준수 예: `OrderCancelService.java:62,161,180,187,196`.
- 근거:
  - `ProblemDetailMappings.GENERIC` ✔는 `IllegalArgumentException`과 `IllegalStateException`을 400으로 매핑하고, 메시지 원문을 그대로 detail로 쓴다. `NoSuchElementException`만 고정 문구다.
  - 자동 분류(배치·어댑터 포함, 188건): 마침표 없음 88, 마침표 없음+내부값 50, 준수 40, 준수+값 5, 영어 5.
  - 배치·내부 경로 메시지는 사용자에게 노출되지 않는다. 그래서 위반은 사용자 요청 경로(web 컨트롤러 → 서비스·도메인 검증)로 한정한다. 경로별 분류는 미확인이다.
- 예상 효과: 사용자 응답 문구를 일관되게 하고, 내부값(enum·UUID) 노출을 차단한다.
- 위험: 낮음. kista-ui는 문구가 아니라 `code`로 분기한다(`ErrorCode`). 단, UI가 detail 문자열을 그대로 비교하는 곳이 있는지는 착수 시 kista-ui에서 grep한다.
- 가드(테스트 초안): trading-core 테스트에 소스 스캔 테스트를 추가한다. 대상은 `application`·`domain` 패키지 소스에서 `new (IllegalArgumentException|IllegalStateException)\("` 리터럴이 `."`나 `.")`로 끝나지 않는 것이다. 이를 실패로 보고, 현재 위반은 allowlist로 동결한 뒤 줄여 간다. 내부 무결성 ISE는 대상 패키지에서 빼거나 `NoSuchElementException`(고정 문구)으로 바꾸는 방향을 착수 시 결정한다.
- 상태: open.

### CQ-06 로그만 남기고 대체값을 돌려주는 raw catch (`BrokerCallGuard.tryGet` 대상)
- 주제: 브로커·시세 호출 실패 처리. 유형: 비일관성.
- 위치:
  - `trading/application/service/ManualTradingService.java:143`. `priceFetcher.fetchPrices`를 호출하고, 실패하면 warn 후 null을 돌려준다.
  - `stats/application/service/CryptoMarketIndicatorService.java:118,134,152,210,256` ✔. 외부 시세를 호출하는데, 5곳 모두 `catch (Exception)` → `warn(라벨, e)` → null 또는 빈값 순서다.
- 기준 패턴: `BrokerCallGuard.tryGet(label, call)`(fail-open, 15곳 사용)과 `wrap`(fail-closed). raw catch는 이벤트 발행이나 분기 처리가 필요할 때만 허용한다(`constraints.md` "브로커 호출 실패 정책").
- 근거: 위 6곳은 이벤트도 분기도 없는 "조회 → 대체값" 형태라 `tryGet`과 의미가 같다. 차이는 로그다. `tryGet`은 `RuntimeException`만 잡고 스택 없이 `e.toString()`을 남긴다(`BrokerCallGuard.java:40-44`). 위 6곳은 `Exception`을 잡고 스택을 남긴다.
- 예상 효과: fail-open 경로를 하나로 통일하고 `CryptoMarketIndicatorService`의 반복 5벌을 제거한다.
- 위험: 낮음. 로그 형태만 바뀐다(스택 → toString). 체크 예외를 던지는 호출이 있으면 `tryGet`으로 바꿀 수 없으니 착수 시 확인한다.
- 조건부 대상: 어댑터 2곳(`trading/adapter/out/feargreed/CryptoFearGreedIndexAdapter.java:34`, `CnnFearGreedIndexAdapter.java:33`)도 같은 "warn 후 `Optional.empty`" 형태다. `BrokerCallGuard`는 `broker/application/service/BrokerCallGuard.java` ✔에 있다. adapter.out이 다른 모듈의 `application.service`를 참조해도 되는지(`HexagonalArchitectureTest`·Modulith NamedInterface)는 미확인이다. 착수 시 확인해, 허용되면 포함하고 금지되면 유지 목록으로 옮긴다.
- 가드(`constraints.md` 기존 규칙에 덧붙일 문장 초안): "이벤트·분기 없이 로그 후 대체값만 반환하는 경우는 예외 없이 `tryGet`을 쓴다."
- 상태: open.

### CQ-07 로그 문맥 접두사 형식 혼재
- 주제: 로깅 형식. 유형: 비일관성.
- 위치:
  - 기준 형식 `[strategyId={}]`, 29건:
    - `CycleRotationService.java:48,63,92,95,98`
    - `VrCycleRolloverService.java:50,55,64,70,84,90,107,126,133,145,164`
    - `CyclePositionPersistor.java:57,66,93,96`
    - `TradingBatchGuard.java:33,44,51,61`
  - 벗어난 곳(본문 중간 `strategyId=`):
    - `trading/application/service/StrategyService.java:70,87,100,187,206`
    - `TradingPreviewService.java:150,171`(`strategyId={}, error={}`)
    - `ReorderService.java:120,139`
  - 같은 혼재가 `orderId`(`[orderId={}]`은 `TradingReporter.java:111-161`, 본문 `orderId=`는 `OrderCancelService.java:106-200`)와 `accountId`(`[accountId={}]`은 `CryptoDcaRunner.java:95`, 본문은 `AccountService.java:96,141,154`)에도 있다.
- 기준 패턴: `[strategyId={}] 한글 메시지`. 전략 문맥이 있는 로그의 다수 형식이다.
- 근거: 문맥 접두사 변형 집계(268건/86파일 중): `[{}]` 49, 본문 `key=` 32, `[strategyId={}]` 29, `[orderId={}]` 6, `[userId={}]` 3, `[accountId={}]` 1.
- 예상 효과: 운영 로그를 `[strategyId=` grep 한 번으로 추적할 수 있다.
- 위험: 낮음. 로그 문자열만 바뀐다. 운영 대시보드·알림이 로그 문자열을 파싱하는지는 착수 시 `docker-infra.md`에서 확인한다.
- 가드(`constraints.md` 주석 규칙 옆에 추가할 문장 초안): "로그 문맥은 `[strategyId={}]`·`[accountId={}]`·`[orderId={}]`·`[userId={}]` 접두사로 시작한다. 본문 중간 `key=`로 쓰지 않는다."
- 상태: open.

### CQ-08 `CyclePosition.toBalance()`가 있는데 인라인 생성
- 주제: 매핑. 유형: 보일러플레이트.
- 위치 ✔: `trading/application/service/support/TradingBalanceLoader.java:29,37`. `new AccountBalance(h.holdings(), h.avgPrice(), h.usdDeposit())` 형태다.
- 기준 패턴: `trading/domain/model/CyclePosition.java:52` `toBalance()`. 이 메서드를 쓰는 곳은 `CryptoDcaRoundExecutor.java:113`, `CryptoLedgerRecorder.java:45`, `StrategyDetailAssembler.java:170`이다.
- 근거: 같은 3필드 변환이 이 파일에서만 인라인돼 있다.
- 예상 효과: 보일러플레이트 제거.
- 위험: 낮음.
- 가드: 없음(단발).
- 상태: open. 파일을 건드리면 즉시 수정한다.

### CQ-09 소유권 검증의 `verifyOwnedBy` 직접 호출 1곳
- 주제: 소유권 검증. 유형: 비일관성.
- 위치: `stats/application/service/InvestmentPointsQueryService.java:55`. `accountPort.findByIdOrThrow(...).verifyOwnedBy(userId)` 형태다.
- 기준 패턴: `AccountPort.requireOwnedAccount(accountId, requesterId)`. 33건/13파일이 이 방식이다.
- 근거: 결과(403)는 같고 호출 형태만 다르다.
- 예상 효과: 검증 경로 일원화.
- 위험: 낮음.
- 가드: 없음(단발).
- 상태: open.

### CQ-10 `MeritzPriceApi` 다중 티커 루프 4벌
- 주제: 브로커 어댑터 중복. 유형: 보일러플레이트.
- 위치: `broker/adapter/out/meritz/MeritzPriceApi.java:73-95`. `getPrices`, `getPriceSnapshots`, `getPrevCloses`, `getClosingPrices`가 모두 `for (t : tickers) result.put(t, getX(t, account))` 형태다.
- 기준 패턴: 파일 안 private 헬퍼 하나로 묶는다. 예: `perTicker(tickers, t -> getX(t, account))`.
- 근거: 같은 루프가 한 파일에 4번 있다. 메리츠가 단건 API만 제공해서인지는 미확인이다. 그렇더라도 루프 모양이 같다.
- 예상 효과: 보일러플레이트 제거.
- 위험: 낮음.
- 가드: 없음.
- 상태: open.

### CQ-11 같은 입력 검증의 다중 정의·이중 검증
- 주제: 입력 검증. 유형: 중복.
- 위치:
  - VR 리밸런싱 주기 1 이상 검증이 3곳에 있다. 문구는 거의 같다.
    - `trading/domain/creation/VrRampValidator.java:26`
    - `VrRegistration.java:41`
    - `stats/application/service/BacktestService.java:124`
  - Bean Validation과 서비스가 같은 값을 이중으로 검증한다. `trading/domain/model/ReorderCommand.java:22-23`이 `@NotNull @Positive`로 검증하고, `ReorderService.java:213,220`이 "price는 양수여야 합니다"로 다시 검증한다.
- 기준 패턴: VR 파라미터 검증은 `VrRampValidator` 하나로 모은다. `@Positive`가 붙은 필드는 서비스에서 다시 검증하지 않는다.
- 근거: 같은 규칙이 바뀌면 3곳을 함께 고쳐야 한다.
- 예상 효과: 검증 규칙 SSOT 확보.
- 위험: 낮음. 백테스트 쪽은 필드명(`vrIntervalWeeks`)이 달라 호출 형태를 맞춰야 한다.
- 가드: 없음.
- 상태: open.

### CQ-13 같은 의미 오류의 예외 타입 분기
- 주제: 입력 검증·예외. 유형: 비일관성.
- 위치:
  - "활성 사이클 없음":
    - IAE 또는 ISE: `BatchContextFactory.java:54`, `CryptoDcaRunner.java:112`
    - `NoSuchElementException`: `TradingPreviewService.java:77`
  - 상태 위반:
    - ISE: `StrategyService.java:80,96`("이미 일시중지된 전략입니다.")
    - IAE: `ManualTradingService.java:59`("ACTIVE 상태의 전략만 수동 실행 가능합니다")
  - "주문을 찾을 수 없습니다":
    - IAE: `ReorderBuyBudgetService.java:37-46`
    - `NoSuchElementException`: `PrivacyTradePersistenceAdapter.java:221`
- 기준 패턴: 리소스 부재는 `NoSuchElementException`(404, 고정 문구), 상태 위반은 `IllegalStateException`, 입력 위반은 `IllegalArgumentException`이다(`ProblemDetailMappings.GENERIC`).
- 근거: 같은 의미가 404 또는 400으로 다르게 응답된다.
- 예상 효과: HTTP 상태 일관성.
- 위험: 낮음~중간. 응답 상태코드가 바뀐다. kista-ui가 상태코드로 분기하는지는 착수 시 확인한다.
- 가드: CQ-05 테스트와 함께 `constraints.md` "예외 매핑 SSOT"에 "부재=NoSuchElement, 상태=ISE, 입력=IAE" 한 줄을 추가한다.
- 상태: open.

### CQ-14 KIS·Toss 일반 호출의 429가 `BrokerRateLimitException`으로 변환되지 않음
- 주제: 브로커 실패 처리. 유형: 비일관성.
- 위치:
  - Upbit와 Binance는 HTTP 계층에서 429/418을 `BrokerRateLimitException`으로 변환한다: `UpbitHttpClient.java:82-110`, `BinanceHttpClient.java:104-146`.
  - Meritz는 `MeritzHttpClient.java:133`과 `MeritzAuthApi.java:85` 양쪽에서 변환한다.
  - KIS와 Toss는 인증 API(`KisAuthApi.java:107-167`, `TossAuthApi.java:118-145`)에서만 변환한다. 일반 호출의 429는 `KisApiException`/`TossApiException`으로 나간다.
- 기준 패턴: 벤더 rate limit은 어댑터 HTTP 계층에서 `BrokerRateLimitException`으로 변환한다(3사 다수).
- 근거: `BrokerCallGuard.isBrokerTyped`와 `BrokerRouter.java:138-142`(`adminFirst`)는 `BrokerRateLimitException`만 429로 보존한다. 그래서 KIS·Toss 일반 호출의 429는 다른 경로로 처리된다. `TradingExceptionHandler`에서 `BrokerApiException` 하위가 어떤 상태로 매핑되는지는 미확인이다.
- 예상 효과: 관리자·사용자 응답에서 rate limit을 일관되게 식별한다.
- 위험: 중간. 주문 POST 재시도 정책과 얽힌다. KIS는 주문 POST를 재시도하지 않는다. 확정 기각된 "KisAuthApi/TossAuthApi catch 공통화"와는 대상(인증 vs 일반 호출)이 다르다.
- 가드: 착수 시 결정한다. 어댑터별 HTTP 클라이언트 테스트에 "429 → BrokerRateLimitException" 단언을 넣는다.
- 상태: open.

### CQ-15 전략 detail 저장의 타입 if/else 라우팅
- 주제: 전략 타입별 분기. 유형: OOP(capability 대체).
- 위치 ✔: `trading/application/service/StrategyCreationPersister.java:53-58,73-75`. `isInfinite`, `isVr`, `isDca` 순서로 if/else한다.
- 기준 패턴: `StrategyCreationResolvers.of(type)` 라우터(`CycleStrategyBeanConfig` 배선). 타입별 detail 저장을 resolver의 capability로 옮긴다.
- 근거: 등록 해석은 라우터를 쓰는데 저장만 타입 분기다. 같은 파일에서 두 번 분기한다. 전략 타입을 추가할 때 누락될 지점이다.
- 예상 효과: 타입 추가 시 수정 지점 축소.
- 위험: 중간. 등록 트랜잭션 경로를 건드린다.
- 가드: CQ-03의 규칙 문장이 덮는다.
- 상태: open.

### CQ-16 시드타입 강제 규칙이 capability 밖에 있음
- 주제: 전략 타입별 분기. 유형: OOP.
- 위치: `trading/application/service/StrategyCreationService.java:107,110`. VR과 DCA는 시드타입을 NONE으로 강제한다.
- 기준 패턴: `StrategyType.capability()`(`StrategyCapability`). 타입별 정적 정책은 여기에 둔다.
- 근거: 시드 정책이 capability에 없고 서비스 분기로만 존재한다(`StrategyCapability` 필드: `requiresPrivacyBase`, `supportsReverseMode`, `divisionCounts`, DCA 선택지).
- 예상 효과: 정책 SSOT 확보.
- 위험: 중간.
- 가드: CQ-03의 규칙 문장이 덮는다.
- 상태: open.

### CQ-17 현재 시각 취득 방식 혼재 (정적 now vs Clock 주입)
- 주제: 날짜·시간대. 유형: 비일관성. 과설계도 일부 기록한다.
- 위치:
  - 정적 `LocalDate.now(TimeZones.KST)` 20곳 ✔:
    - `broker/adapter/out/toss/TossPriceApi.java:157`
    - `broker/adapter/out/meritz/MeritzOrderApi.java:154`
    - `broker/adapter/out/kis/KisTradingApi.java:173`
    - `privacy/adapter/out/persistence/PrivacyTradePersistenceAdapter.java:124`
    - `marketcalendar/adapter/in/schedule/MarketCalendarRefreshScheduler.java:34,52,63`
    - `trading/adapter/in/web/TradingCycleController.java:224,225`
    - `trading/application/service/TradingService.java:79,158`
    - `CycleSnapshotCreator.java:97`
    - `StrategyCreationService.java:135`
    - `OrderCancelService.java:79`
    - `support/StrategyHistoryQueryService.java:124`
    - `trading/domain/model/StrategyCycle.java:44`
    - `stats/application/service/AccountStatisticsService.java:130`
    - `TradingStatsService.java:129,302`
    - `InvestmentPointsQueryService.java:50`
  - 그 밖의 정적 `Instant.now()` 약 30곳은 재현 명령으로 갈음한다: `grep -rn 'Instant.now()' trading-core/src/main/java`.
  - `Clock`을 주입하는 곳은 8곳이다:
    - `OrderSubmissionReconciler.java:41,48`
    - `CryptoMarketIndicatorService.java:68,75-80`
    - `ApiKeyExpiryScheduler.java:30,38`
    - `CryptoDcaScheduler.java:25,30`
    - `UpbitExchangeAdapter.java:48-51`
    - `UpbitMarketDataAdapter.java:44-47`
    - `BinanceExchangeAdapter.java:44-47`
    - `BinanceHttpClient.java:46-49`
    - `TossRedisTokenStore.java:71-74`
  - `DstInfo`는 `xxx()`/`xxx(ZonedDateTime)` 오버로드 쌍으로 시각을 주입한다(`DstInfo.java:72,76`, `:96,99`, `:132,136`).
  - 테스트용 package-private 생성자를 이중으로 두는 패턴이 7곳에 있다(위 Clock 주입 목록).
  - 스케쥴러 2곳의 Clock 필드는 비-final이다(`ApiKeyExpiryScheduler.java:30`, `CryptoDcaScheduler.java:25`).
- 기준 패턴: 미정. 착수 시 사용자 판단으로 정한다. 후보는 (a) 정적 now + 시각 파라미터 오버로드(DstInfo 방식, 다수), (b) `Clock` 빈 주입이다.
- 근거: 시각 주입 방식이 세 가지로 갈린다.
- 예상 효과: 테스트 시각 고정 방식 통일.
- 위험: 낮음. 범위가 넓어 단건 정리로는 효과가 작다. 기준을 먼저 정해야 한다.
- 가드: 기준이 정해지면 `constraints.md` 규칙 문장으로 둔다.
- 상태: open(기준 결정 대기).

### CQ-18 `TradingCycleResponse.forRegistration`의 15필드 재나열
- 주제: 매핑. 유형: 보일러플레이트.
- 위치: `trading/adapter/in/web/dto/TradingCycleResponse.java:115-117`. `from(detail)`의 결과를 15필드 나열로 다시 만들고 마지막 boolean 하나만 바꾼다.
- 기준 패턴: `with*()`. 이 record에는 `with*()`가 없다. `withX` 하나를 추가하거나 `from`에 인자를 넘긴다.
- 근거: 필드를 추가할 때마다 두 곳을 맞춰야 한다.
- 예상 효과: 보일러플레이트 제거.
- 위험: 낮음.
- 가드: 없음.
- 상태: open. 파일을 건드리면 즉시 수정한다.

### CQ-19 예외 로그의 스택 포함 여부 혼재
- 주제: 로깅 형식. 유형: 비일관성.
- 위치:
  - 전수 목록은 재현 명령으로 갈음한다: `grep -rnE 'log\.(warn|error)\(' trading-core/src/main/java`. 결과를 마지막 인자가 `e.getMessage()`인 줄과 `e`인 줄로 나눈다.
  - `e.getMessage()`만 남기는 곳 48건. 대표: `OrderSubmitter.java:47,78,97,115,149,164`, `OrderSubmissionReconciler.java:94,97,113`, `TradingPriceFetcher.java:178,192`, `ManualTradingService.java:81,118,144`.
  - 예외 객체를 넘겨 스택을 남기는 곳 28건. 대표: `CryptoMarketIndicatorService.java:119,135,153,211,257`, `TradingService.java:297`, `CycleRotationService.java:92`.
- 기준 패턴: 미정. 후보는 "예상된 브로커 거절·시세 실패는 `getMessage()`, 예상 밖 오류(관리자 알림 경로)는 예외 객체 전달"이다. 같은 시세 실패가 `TradingPriceFetcher`에서는 메시지만, `CryptoMarketIndicatorService`에서는 스택으로 남는다.
- 근거: warn·error 158건 중 위 분포.
- 예상 효과: 장애 분석 시 스택 유무를 예측할 수 있다.
- 위험: 낮음.
- 가드: 기준이 정해지면 `constraints.md` 문장으로 둔다.
- 상태: open(기준 결정 대기).

### CQ-12 같은 필드 기본값을 여러 층에서 지정
- 주제: null·기본값. 유형: 중복·비일관성.
- 위치:
  - `recurringAmount` null → 0:
    - `trading/adapter/in/web/dto/TradingCycleRequest.java:68`
    - `trading/domain/creation/VrCreationResolver.java:29`
    - `VrRegistration.java:14,44`
    - `trading/application/service/VrStrategyDetailService.java:31`
  - `divisionCount`:
    - DTO는 null → 0(`TradingCycleRequest.java:65`)이다.
    - resolver는 `DEFAULT_DIVISION_COUNT`(20)를 쓴다: `PrivacyCreationResolver.java:18`, `DcaCreationResolver.java:18`, `VrCreationResolver.java:23`.
    - 조회 폴백은 `.orElse(DEFAULT_DIVISION_COUNT)`다: `CyclePositionPersistor.java:86`, `CycleRotationService.java:79`, `StrategyDetailAssembler.java:49,128`, `CycleOrderComputer.java:132,136`.
    - matching 쪽: `InfiniteCycleOrderStrategy.java:113,129` ✔.
  - `initialUsdDeposit` null → ZERO:
    - `StrategyCreationService.java:115`
    - `StrategyCreationPersister.java:64`
    - `VrRegistration.java:58`
  - `broker` null → KIS:
    - `account/adapter/in/web/AccountController.java:121`
    - `account/application/service/AccountService.java:46`
- 기준 패턴: `constraints.md` "persistence 기본값". 기본값은 도메인 생성 시점 한 곳에서 정한다.
- 근거: 위치마다 기본값을 각각 지정한다. DTO의 divisionCount 0과 resolver의 기본값 20이 어떻게 상호작용하는지(0이 기본값으로 대체되는지 그대로 저장되는지)는 미확인이다. 착수 시 `RegisterStrategyCommand` → resolver 경로를 Read한다.
- 예상 효과: 기본값을 한 곳에서만 지정하고, 0과 20의 불일치 여지를 제거한다.
- 위험: 높음(공식). 전략 등록 결과가 바뀔 수 있고, INFINITE 분할 수는 공식 입력이다. 비공식 필드(broker 기본값 등)만 먼저 분리해 진행할 수 있다.
- 가드(`constraints.md` "persistence 기본값" 확장 문장 초안): "web DTO의 `toCommand()`도 기본값을 정하지 않는다. null을 그대로 넘기고 도메인 resolver·팩토리가 정한다."
- 상태: open.

### CQ-20 USD 금액 scale 리터럴 2 산재와 `MONEY_SCALE` 이중 정의
- 주제: 금액·수량 스케일. 유형: 중복.
- 위치:
  - `setScale(2, RoundingMode.HALF_UP)` 36건 ✔:
    - matching:
      - `matching/domain/model/ReverseModePosition.java:66`
      - `DcaIndicators.java:41`
      - `StrategyVrDetail.java:43,47`
      - `matching/domain/strategy/InfiniteCycleOrderStrategy.java:148`
      - `DcaConditions.java:73,103`
      - `PriceCapPolicy.java:23`
    - broker:
      - `broker/adapter/out/meritz/MeritzOrderApi.java:208`
      - `broker/adapter/out/kis/KisResponseParser.java:43`
      - `broker/domain/model/PresentBalanceResult.java:48,51,56,90,96`
    - privacy: `privacy/adapter/out/persistence/PrivacyTradePersistenceAdapter.java:118`
    - trading:
      - `trading/adapter/out/feargreed/CryptoFearGreedIndexAdapter.java:33`
      - `CnnFearGreedIndexAdapter.java:32`
      - `trading/adapter/out/persistence/StrategyDcaRuleEntity.java:106`
      - `StrategyDcaConditionEntity.java:63`
      - `trading/application/service/CycleSnapshotCreator.java:103`
      - `CycleOrderComputer.java:99`
      - `VrStrategyDetailService.java:75`
      - `VrCycleRolloverService.java:105,143`
      - `VrReconfigureService.java:184`
      - `StrategyCreationService.java:104`
      - `trading/domain/model/CyclePosition.java:69`
      - `CyclePositionHistoryEntry.java:30,36`
    - stats:
      - `stats/adapter/out/mempool/MempoolBlockHeightAdapter.java:48`
      - `stats/application/service/TradingStatsService.java:287,288,311`
      - `stats/domain/backtest/BacktestEngine.java:388,399`
  - 이 외에 `setScale(MONEY_SCALE, HALF_UP)`, 다른 RoundingMode, `divide(x, 2, HALF_UP)` 형태는 재현 명령으로 찾는다: `grep -rnE 'setScale\(|divide\([^)]*, 2,' trading-core/src/main/java`.
  - `MONEY_SCALE = 2` private 이중 정의 ✔: `matching/domain/model/InfinitePosition.java:16`, `VrPosition.java:16`.
  - 공식 경로의 리터럴:
    - `ReverseModePosition.java:42,66`
    - `PriceCapPolicy.java:23`
    - `InfiniteCycleOrderStrategy.java:148`
    - `PrivacyCycleOrderStrategy.java:47`
    - `VrRampValidator.java:77`
    - `CycleOrderComputer.java:122`
    - `BacktestEngine.java:388,399,570`
    - `VrCycleRolloverService.java:105,143`
    - `VrReconfigureService.java:184`
- 기준 패턴: matching `kernel`에 공용 상수를 하나 둔다. 예: `Money.USD_SCALE`. 위치는 착수 시 `own-type-ledger.md`와 sharedkernel 승격 여부로 판단한다.
- 근거: 같은 의미(USD 센트 단위)가 상수 2벌과 리터럴 약 40곳으로 표현된다.
- 예상 효과: 스케일 의미 명시.
- 위험: 높음. 공식 코드가 대부분이다. 값은 같으므로 치환만 하고, 공식 테스트 통과를 확인한다. 비공식 경로(어댑터·통계·표시)부터 분리해 진행할 수 있다.
- 가드: 상수 도입 후 `constraints.md`에 "USD 금액 반올림은 `<상수>`를 쓴다" 한 줄.
- 상태: open.

### CQ-21 백테스트 러너 선택의 타입 switch
- 주제: 전략 타입별 분기. 유형: OOP.
- 위치: `stats/domain/backtest/BacktestEngine.java:70-72,81` ✔. `switch (command.type())`로 `runVr`/`runInfinite`를 고르고, PRIVACY는 별도 오버로드다. 같은 클래스가 `CycleOrderStrategies`를 이미 쓴다(`:218,279,351`).
- 기준 패턴: `CycleOrderStrategies` capability.
- 근거: 주문 계산은 capability를 쓰는데, 러너 선택만 타입 switch다. `BacktestService.java:65,70,76,84,108,115`에도 타입 분기 6곳이 있다.
- 예상 효과: 백테스트 지원 타입을 추가할 때 수정 지점이 줄어든다.
- 위험: 높음. 백테스트 결과가 공식 계산을 재현한다.
- 가드: CQ-03의 규칙 문장이 덮는다.
- 상태: open.

### CQ-22 평단가 scale 4와 가격 scale 2 혼재
- 주제: 금액·수량 스케일. 유형: 비일관성(의도 확인 필요).
- 위치:
  - 평단 4자리: `matching/domain/model/AccountBalance.java:82`, `VrReconfigureService.java:165`, `PresentBalanceResult.java:54`.
  - 가격 2자리: CQ-20 참조.
- 기준 패턴: 미정. `trading-formulas.md`는 `averagePrice`의 scale을 명시하지 않는다.
- 근거: 같은 USD 단가가 경로에 따라 4자리 또는 2자리다. 의도 여부는 미확인이다.
- 예상 효과: 원장 평단 표시 일관성.
- 위험: 높음. 평단은 INFINITE·VR 주문가 계산의 입력이다.
- 가드: 결론 후 `trading-formulas.md`에 averagePrice scale을 명시한다.
- 상태: open(의도 확인 대기).

## 유지 목록 (재제안 금지, 이유 기록)

| 발견 | 위치 | 유지 이유 |
|---|---|---|
| `CycleHistoryResponse`/`CycleHistoryPageResponse` trading·stats 완전 복제 | `trading/adapter/in/web/dto/`, `stats/adapter/in/web/dto/` (package 줄 외 diff 없음 ✔) | `own-type-ledger.md`가 (b) 외부 계약 분리로 허용한 항목이다. 엔드포인트별 응답 스키마. |
| `TossPriceApi`의 DstInfo 세션 계산 복제 | `broker/adapter/out/toss/TossPriceApi.java:161-199` ✔ | broker는 trading 도메인을 참조할 수 없다. 주석이 이유와 "common 승격 대상 아님"을 명시한다. NY 상수만 CQ-01에서 처리한다. |
| `TradingBatchResumer` 04:30 경계 주석 | `trading/adapter/in/schedule/TradingBatchResumer.java:84` ✔ | 요일 조합 판정이라 `nextTradeDate()`와 계산이 다르다. 경계값 문서화 주석이다. |
| `MarketSessionSnapshot` 세션 계산 | `marketcalendar/domain/model/MarketSessionSnapshot.java:11,16` | marketcalendar는 trading을 참조하지 않는다. NY 상수만 CQ-01. |
| `TradingService` 배치 `LocalDate.now(KST)` | `TradingService.java:79,158` ✔ | 체크포인트 키(같은 날짜 수동 실행 DONE과 비교)라 거래일 값이 아니다. 주석에 명시돼 있다. |
| `CycleSnapshotCreator` `LocalDate.now(KST)` | `CycleSnapshotCreator.java:97` | 주석에 "r.today()(다음 거래일)와 다르다"고 명시돼 있다. |
| `DcaSummaryAssembler` `plusDays(1)` | `DcaSummaryAssembler.java:58-62` ✔ | KST 안에서 다음 실행 후보일을 계산한다. 기준 변환(KST↔US, 발행일↔거래일)이 아니다. |
| 조회 범위 `minusDays`/`plusDays` | `KisTradingApi:174`, `MeritzOrderApi:156-157`, `TradingCycleController:224`, `UpbitExchangeAdapter:118` 등 | 단순 조회 창이다. 기준 변환이 아니다. |
| stats 크립토 `ZoneOffset.UTC` | `CoinMarketCapAdapter`, `CoinbaseMarketDataAdapter`, `MempoolBlockHeightAdapter`, `BinanceMarketDataAdapter`, `CryptoPremiums:81`, `CryptoMarketIndicatorService:110,159,248` | 외부 원본(UTC 일봉)을 그대로 유지한다(`constraints.md` "외부 원본 참조 데이터"). Toss 캔들 커서(`TossPriceApi.java:57,99`)가 UTC인 것이 API 요구인지는 미확인이다. |
| 총자산 계산 5곳 | `InfinitePosition.java:43`, `CycleSnapshotCreator.java:100-104`, `CyclePosition.java:66-70`, `CyclePositionHistoryEntry.java:36`, `VrCycleRolloverService.java:142` ✔ | 의미가 서로 다르다: 종가/평단 폴백, scale 유무, 평가액 기준, 종료금액 계약. `VrCycleRolloverService`는 공식이다. |
| 수량 내림 FLOOR vs DOWN | `VrCycleOrderStrategy.java:85`, `VrStrategy.java:99`(DOWN), 그 외 FLOOR | 양수 입력에서 결과가 같다. 공식 코드를 표기 통일만 위해 바꾸는 이득이 비용보다 작다. |
| `InfinitePosition:119` scale 없는 divide | `matching/domain/model/InfinitePosition.java:119` | 공식 코드라 변경하지 않는다. 기록만. |
| `TradingStatsService` 비율 scale 1/4/6 | `TradingStatsService.java:233,240,242,248,301` | 용도가 다르다: 중간값 6, 결과 4, 기간 평균 1. |
| `CRYPTO_SCALE = 8` 2벌 | `BootstrapPosition.java:13`, `ManualTradeCorrectionService.java:49` ✔ | 2곳(추출 기준 미달). 근거 주석도 다르다(DB numeric vs 업비트 상한). |
| 가격 문자열 포맷 2곳 | `MeritzOrderApi.java:208`, `KisResponseParser.java:43` | 2곳, 증권사 wire 포맷. |
| 라이브·백테스트 별지점 평균 | `CycleOrderComputer.java:122`, `BacktestEngine.java:570` | 2곳(추출 기준 미달). 공식이다. 한쪽만 고치면 불일치하므로 공식 변경 시 양쪽을 같이 본다. |
| prefix 평균 2곳 | `CryptoValuations.java:57`, `CryptoHashRibbons.java:85` | 2곳. |
| 페이지 슬라이싱 2곳 | `StrategyHistoryQueryService.java:103-107`, `AccountStatisticsService.java:118-122` | 2곳. |
| `isPrivacy` 기준표 외 단순 타입 가드·필터·표시 | `DcaExecutionQueryService:39`, `DcaConfigService:34`, `VrReconfigureService:59`, `ManualTradingService:57`, `TradingStatsService:136,150`, `TradingCycleResponse:100`, `CycleHistoryResponse:28` | 동작 분기가 아니라 타입 가드·필터·표시다. CQ-03 규칙의 허용 범위. |
| `StrategyDetailAssembler` 단건/일괄 분기 두 벌 | `StrategyDetailAssembler.java:40-65`, `117-149` | 단건과 일괄(N+1 회피) 조회 형태가 달라 의도적으로 둘이다. 순수 조립은 이미 `assemble()`로 공용화돼 있다. |
| `DcaCycleOrderStrategy.withLimits` 9필드 복사 | `matching/domain/strategy/DcaCycleOrderStrategy.java:85-87` | 이미 파일 안 헬퍼로 한 곳에 모였다. 호출처는 이 파일뿐이다. |
| 주문 상태 판정 서비스 산재 | `ReorderService:76,150,193`, `ReorderBuyBudgetService:69`, `OrderCancelService:60,159,163,177,185`, `DcaPlanInputCollector:157`, `CryptoOrderSynchronizer:64,70`, `OrderSubmissionReconciler:86`, `TradingReporter:102`, `TradingPreviewService:197` | 같은 조합은 `PLANNED \|\| PLACED` 2곳뿐이고, 나머지는 조합이 제각각이다. 같은 이유로 바뀌는 3곳 이상이 없다. 같은 조합이 3곳이 되면 `Order` 판정 메서드로 올린다. |
| `TradingErrorEvent(null, ...)` 29곳 | 13파일 ✔ | 생성자 한 줄이라 헬퍼로 묶어도 이득이 없다. |
| `VrCycleRolloverService` 등 `e.getMessage()`만 보내는 알림 | `VrCycleRolloverService:51,86`, `VrReconfigureService:118` | 문맥 보강은 CQ-07·CQ-19의 기준이 정해지면 함께 본다. 별도 항목으로 두지 않는다. |
| 소유권 응답 코드 400/404/403 | `SelectionChain.java:27-41`, `StrategyStatusInternalController.java:34`, `TradingInternalQueryController.java:90-95` | `SelectionChain` 호출처는 `ReorderService`·`ManualTradeCorrectionService`뿐이다 ✔. 둘 다 내부 토큰 경로(root admin 대리 호출)로, userId가 바디로 들어와 입력 오류(400)가 맞다. 소유권 중복 제거는 기각 확정(2026-07). |
| `TradingStatsService`·`PortfolioService` 필터 방식 | `TradingStatsService.java:148-151`, `PortfolioService.java:25,33` | userId 범위로 조회해 남의 accountId는 빈 결과가 된다(주석 명시). 검증 누락이 아니다. |
| 내부 컨트롤러 소유권 검증 없음 | `PrivacyBaseInternalController`, `PrivacyInternalQueryController`, `FidaOrderController`, `AccountInternalController` | `/api/internal/**` X-Internal-Token 경로라 설계상 의도다. |
| `TradingCycleController` update에 `@Valid` 없음 | `TradingCycleController.java:92` | 같은 DTO의 `@NotNull type`(`TradingCycleRequest.java:18-19` ✔, "등록 시 필수")이 수정 요청에는 맞지 않는다. |
| `AccountController` 이름 변경에 `@Valid` 없음 | `AccountController.java:68` | 등록 전용 제약(@Pattern 계좌번호) 때문이며 주석에 명시돼 있다. 서비스가 별명만 수동 검증한다. |
| `DcaConfigRequest` 등 DTO compact constructor의 IAE | `DcaConfigRequest.java:17-18`, `DcaRuleRequest.java:34` | "[field: message]" 형식 대신 사용자 문구로 400을 내려는 의도이며 주석(`:16`)에 있다. |
| `UserNotifyProfilePersistenceAdapter` `occurredAt` 폴백 | `UserNotifyProfilePersistenceAdapter.java:86` | 이벤트 필드 주석이 "null이면 구버전 발행분"이라고 명시한다 ✔(`shared/kernel/UserNotifyProfileChangedEvent.java:18`). 레거시 nullable 예외다. |
| persistence SUM null → 0 | `OrderPersistenceAdapter.java:216,251` | 매퍼가 아니라 집계 쿼리 결과 처리다. |
| `BrokerCallGuard` 밖 raw catch(이벤트·분기 있음) | `ManualTradingService:80,117`, `ReorderService:119`, `OrderSubmitter:66`, `OrderSubmissionReconciler:92,112`, `CryptoDcaRoundExecutor:135,201`, `CryptoDcaRunner:79,94`, `TradingService:295,399`, `TradingBatchGuard:32`, `VrCycleRolloverService:49,83`, `TradingReporter:115`, `TradingOrderExecutor:176`, `CycleRotationService:87`, `PrivacyBaseGuard:32`, `BrokerRouter:138-142` | `constraints.md`가 허용하는 이벤트 발행·분기 처리 예외다. |
| 재시도·fallback이 있는 raw catch | `TradingPreviewService:148,170`, `TradingPriceFetcher:177,191`, `CryptoOrderSynchronizer:54`, `UserTradingDataEraseService:47` | 재시도·부분 실패 맵·다음 틱 재시도 같은 분기가 있다. |
| `validateInfiniteMinSeed` fail-open | `StrategyCreationService.java:154` | 의도된 동작(2026-10-31 확정). |
| `KisAuthApi`/`TossAuthApi` catch | `KisAuthApi:107-167`, `TossAuthApi:118-145` | 공통화 기각 확정(2026-07). |
| 증권사별 백오프·인터럽트 처리 4벌 | `TossHttpClient.java:72,115`, `KisHttpClient.java:202-204`, `MeritzHttpClient.java:184-186` | 인터럽트 시 동작(전파, false, 예외)이 각 클라이언트의 재시도 계약(주문 중복 위험)에 묶여 있다. 지수+지터 식이 같은 것은 KIS·Meritz 2곳뿐이다. |
| Upbit·Binance `execute/translate` 동형 | `UpbitHttpClient.java:82-110`, `BinanceHttpClient.java:104-146` | 2곳. 오류 코드 해석은 wire 포맷(`error.name` vs 음수 `code`)이다. |
| 브로커 응답 숫자 파서 3벌 | `KisResponseParser.java:23,29`, `TossResponseParser.java:13,18`, `MeritzResponse.java:70,75` | 빈값 의미가 증권사 wire별로 다르다(ZERO, null, 예외). |
| RestClient 설정 빈 5개 | `TossConfig:19`, `UpbitConfig:15`, `MeritzConfig:15`, `KisConfig:18`, `BinanceConfig:14` | 이미 `HttpClientTimeouts`로 공용화돼 있다. 남은 차이(baseUrl 유무, KIS 7초)는 주석에 근거가 있다. |
| KIS·Meritz 거래소 레지스트리 키 집합 동일 | `KisExchangeRegistry`, `MeritzExchangeRegistry` | 코드값이 증권사 wire다. 티커 추가 시 양쪽 갱신이 필요하다. `MeritzExchangeRegistryTest`는 존재를 확인했고, KIS 쪽 전수 매핑 테스트는 미확인이다. |
| Binance·Upbit 역방향 티커 조회 동형 | `BinanceSymbolRegistry.java:31`, `UpbitMarketRegistry.java:21` | 2곳. |
| 어댑터 `toDomain/toEntity` vs 엔티티 메서드 | `AccountEntity.java:66`, `StrategyDcaRuleEntity.java:87,109`, `StrategyDcaConditionEntity.java:56,67`, `UsMarketHolidayEntity.java:25` | `AccountEntity`는 해시 인자 때문이고, DCA는 부모-자식 조립 때문이다. MapStruct 도입 거부 확정(2026-10-31). |
| web 응답 Mapper 클래스와 DTO `from()` 병존 | `TradingContractMapper`, `StatsContractMapper`, `PrivacyContractMapper` vs `*Response.from` | `Order` → DTO 3종은 포함 필드가 달라 단순 중복이 아니다. contract 매핑(내부 API)과 사용자 응답 DTO는 계약이 다르다. |
| `isCryptoExchange()` 분기 8개 서비스 | `BatchContextFactory:51`, `ReorderService:69`, `OrderCancelService:57,93,159`, `CryptoDcaRunner:62`, `StrategyCreationService:73`, `TradingPreviewService:71,96`, `CryptoPortfolioService:76` | 전략 타입이 아니라 증권사 capability 술어다. 이미 capability 형태다. |
| `BootstrapPosition`·`BacktestEngine` 평단 필수 규칙 | `BootstrapPosition.java:35`, `BacktestEngine.java:420` | 2곳. |
| "사용자를 찾을 수 없습니다" 복제 | `BatchContextFactory.java:71`, `CryptoDcaRunner.java:122` | 2곳. |
| `String accountNo` 원시값 | 어댑터 9개 외 | 포트·어댑터 경계의 wire 값이다. 애그리게이트 내부는 `BrokerAccountRef`가 이미 묶는다. |
| `DstInfo` `xxx()`/`xxxAt()` 오버로드 쌍 | `DstInfo.java:72-184` | 정적 시계를 테스트용으로 분리한 구조다. CQ-17 기준 결정 때 함께 본다. |
