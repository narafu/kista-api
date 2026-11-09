# trading-core 코드 품질 원장

trading-core 감사 결과를 모은 원장이다. 한 번에 리팩토링하지 않는다. 다른 정해진 작업이 그 파일이나 패키지를 건드릴 때 해당 항목을 같이 해소한다.

## 운영 규칙

- 작업이 아래 "위치"에 적힌 파일이나 패키지를 건드리면 그 항목도 같은 작업에서 처리한다. 원장 전체를 읽지 말고 건드리는 파일명으로 grep해(`grep -n '<파일명>' docs/agents/code-quality-backlog.md`) 걸린 항목만 읽는다. 범위가 크면 착수 전에 사용자에게 알린다.
- 처리하면 "상태"를 `done(<커밋 해시>)`로 바꾸고, 그 항목의 "가드"도 함께 넣는다.
- 작업 중 새로 발견한 비일관성·중복은 같은 형식으로 추가한다. 근거는 grep으로 확인한 file:line만 적는다(`own-type-ledger.md` "증거 의무"). 추정은 "미확인"이라고 명시한다.
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

### CQ-00 PRIVACY 상세의 currentRound가 항상 0.0 (null이 맞음, 확정)
- 주제: 매핑·기본값. 유형: 비일관성(버그 의심).
- 위치:
  - `trading/application/service/StrategyDetailAssembler.java:168-172` ✔. 주석은 "INFINITE·PRIVACY만 계산"인데, `divisionCount == null ? 0 : divisionCount`로 넘긴다.
  - `StrategyDetailAssembler.java:46-48,124-127` ✔. divisionCount는 `strategy.isInfinite()`일 때만 채워지므로 PRIVACY는 항상 null이다.
  - `matching/domain/model/InfinitePosition.java:60` ✔. `if (divisionCount == 0 ...) return 0.0`.
- 근거: PRIVACY 전략은 위 경로로 currentRound가 무조건 0.0이 된다. 다른 경로의 기본값은 `StrategyDefaults.DEFAULT_DIVISION_COUNT = 20`이다(`shared/.../StrategyDefaults.java:8`, `CyclePositionPersistor.java:92` 등의 `.orElse(DEFAULT_DIVISION_COUNT)`).
- 확정(사용자, 2026-11-01): divisionCount를 INFINITE에만 채우는 것은 맞다. 이 부분은 수정 대상이 아니다.
- 확정(사용자, 2026-11-01): PRIVACY의 currentRound는 VR·DCA처럼 null로 내린다. 회차 개념은 INFINITE 전용이다.
- 수정안: `StrategyDetailAssembler.java:169`의 조건 `strategy.isVr() || strategy.isDca()`를 `!strategy.isInfinite()`로 바꾸고, `:168` 주석을 "INFINITE만 계산"으로 고친다.
- 예상 효과: PRIVACY 상세 currentRound가 0.0에서 null로 바뀐다(화면 값 변경).
- 위험: 중간. 공식 계산은 바뀌지 않고 표시만 바뀐다. kista-ui가 PRIVACY에서 currentRound를 null로 받아도 깨지지 않는지 확인해야 한다(VR·DCA가 이미 null이라 처리 경로는 있을 가능성이 높으나 미확인).
- 가드: `StrategyDetailAssembler` 단위 테스트에 "INFINITE 외 타입은 currentRound가 null" 단언을 추가한다.
- 상태: done(24b885bc5). 가드: `StrategyServiceTest.equivalence_privacy`에 currentRound null 단언 추가(변이 확인 완료).

## 항목 (효과 대 위험 순, 가드를 먼저 넣으면 재발이 멈추는 항목이 위)

### CQ-01 America/New_York ZoneId 상수 4벌
- 주제: 날짜·시간대. 유형: 중복·비일관성.
- 위치 ✔:
  - `broker/adapter/out/toss/TossPriceApi.java:166`
  - `marketcalendar/domain/model/MarketSessionSnapshot.java:16`
  - `trading/domain/model/DstInfo.java:15`
  - `stats/adapter/out/alpaca/AlpacaCandleAdapter.java:30` (이름은 `NEW_YORK`)
  - 루트 `api/benchmark/adapter/out/alpaca/AlpacaIndexPriceAdapter.java:23` (`NEW_YORK`, 착수 시 추가 발견)
- 기준 패턴: `shared/kernel/TimeZones.KST`(감사 시 경로 오기 정정). 그 클래스 주석이 "`ZoneId.of("Asia/Seoul")` 인라인 반복 금지"다. `TimeZones.NY`를 추가해 5곳이 참조하게 한다.
- 근거: `grep 'ZoneId.of("America/New_York")'` 결과 4건/4파일. `TimeZones`에는 KST만 있다.
- 예상 효과: 시간대 상수의 SSOT를 일원화하고, 신규 코드에서 같은 인라인이 반복되지 않게 막는다.
- 위험: 낮음. 상수만 치환하고 값은 같다.
- 가드(ArchUnit 초안, 루트 `HexagonalArchitectureTest`에 `usTradeDates_must_only_be_used_by_allowlisted_adapters`와 나란히 둔다): `noClasses().that().doNotHaveFullyQualifiedName("com.kista.shared.kernel.TimeZones").should().callMethod(ZoneId.class, "of", String.class)`.
- 상태: done(5e1b51443). 가드 `zoneId_of_must_only_be_called_in_TimeZones`는 테스트의 동적 zone 파싱 때문에 main 클래스만 대상이다.

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
- 상태: done(5e1b51443). 가드 `application_services_must_not_use_TradingErrorReportPort`.

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
- 상태: done(f52f4f67f). `Strategy.requiresPrivacyBase()`(capability 위임)로 기준표 판정 5곳을 일원화했고, `CryptoDcaRunner`는 `Strategy::isDca`를 쓴다.

### CQ-04 KST 일자 → Instant 조회 상한 변환 4곳 반복
- 주제: 날짜·시간대. 유형: 중복.
- 위치 ✔:
  - `trading/application/service/support/StrategyHistoryQueryService.java:119-126`(`resolveHistoryFrom/To`)
  - `stats/application/service/AccountStatisticsService.java:125-132`(`resolveFrom/To`). 위와 문자 그대로 같다.
  - `stats/application/service/TradingStatsService.java:133`
  - `stats/application/service/InvestmentPointsQueryService.java:74`
- 기준 패턴: `atStartOfDay(TimeZones.KST)` 관용구(`modules/shared/platform.md` "시간 기준 정책"). 반개구간 상한 `to.plusDays(1).atStartOfDay(KST).toInstant()`를 `shared/platform/time` 헬퍼 하나로 둔다. 예: `KstDays.endExclusive(LocalDate)`.
- 근거: `grep 'plusDays(1).atStartOfDay(TimeZones.KST)'` 결과 4건/4파일. 셋 다 to가 null이면 `LocalDate.now(TimeZones.KST)`로 채운다.
- 예상 효과: 조회 범위 경계 규칙을 일원화한다.
- 위험: 낮음.
- 가드(`modules/shared/platform.md` "시간 기준 정책"에 추가할 문장 초안): "KST 일자 구간을 Instant로 바꿀 때는 `<헬퍼>`를 쓴다. `plusDays(1).atStartOfDay(...)`를 인라인하지 않는다."
- 상태: done(5e1b51443). 헬퍼는 `shared/kernel/KstDays.endExclusive`에 뒀다(platform은 kernel의 `TimeZones`를 참조할 수 없음 — `platform_must_not_depend_on_other_modules`). null 기본값은 호출부에 남겼다(CQ-17 Clock 주입과 충돌 방지).

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
- 기준 패턴: `modules/api/web.md` "ProblemDetail 응답 계약". detail은 한국어 완전문장 "~습니다."이고 마침표를 찍으며 내부 정보를 싣지 않는다. 준수 예: `OrderCancelService.java:62,161,180,187,196`.
- 근거:
  - `ProblemDetailMappings.GENERIC` ✔는 `IllegalArgumentException`과 `IllegalStateException`을 400으로 매핑하고, 메시지 원문을 그대로 detail로 쓴다. `NoSuchElementException`만 고정 문구다.
  - 자동 분류(배치·어댑터 포함, 188건): 마침표 없음 88, 마침표 없음+내부값 50, 준수 40, 준수+값 5, 영어 5.
  - 배치·내부 경로 메시지는 사용자에게 노출되지 않는다. 그래서 위반은 사용자 요청 경로(web 컨트롤러 → 서비스·도메인 검증)로 한정한다. 경로별 분류는 미확인이다.
- 예상 효과: 사용자 응답 문구를 일관되게 하고, 내부값(enum·UUID) 노출을 차단한다.
- 위험: 낮음. kista-ui는 문구가 아니라 `code`로 분기한다(`ErrorCode`). 단, UI가 detail 문자열을 그대로 비교하는 곳이 있는지는 착수 시 kista-ui에서 grep한다.
- 가드(테스트 초안): trading-core 테스트에 소스 스캔 테스트를 추가한다. 대상은 `application`·`domain` 패키지 소스에서 `new (IllegalArgumentException|IllegalStateException)\("` 리터럴이 `."`나 `.")`로 끝나지 않는 것이다. 이를 실패로 보고, 현재 위반은 allowlist로 동결한 뒤 줄여 간다. 내부 무결성 ISE는 대상 패키지에서 빼거나 `NoSuchElementException`(고정 문구)으로 바꾸는 방향을 착수 시 결정한다.
- 상태: done. 가드 `ExceptionMessageFormatTest`(루트 architecture, root·trading-core·shared의 application·domain 스캔, 종결 기준 `니다.`)를 도입하고 allowlist를 비웠다. 사용자 요청 경로(관리자 포함)는 타입을 유지한 채 한국어 완전문장으로 고치고 enum·UUID·영어 필드명을 뺐다(VR 필드명은 kista-ui 라벨 기준). 배치·내부 무결성 경로의 부재 ISE는 `NoSuchElementException`으로 바꿨다(`CryptoLedgerRecorder`·`CycleRotationService`·`VrCycleRolloverService`·`TradingBalanceLoader`·크립토 지표 도메인). 웹·배치 양쪽 경로는 400→404 변동을 피하려 타입을 유지했다. 응답 상태코드 변경 없음. 안내형 문구("~해 주세요.")도 "~니다."로 통일했다. 미사용 `Order.leg()`는 삭제했다.

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
- 가드: `constraints.md` "브로커 호출 실패 정책"에 "이벤트·분기 없이 로그 후 대체값만 반환하는 경우는 예외 없이 `tryGet`을 쓴다" 문장을 추가했다.
- 상태: done(0f6115559). 서비스 6곳과 공포탐욕 어댑터 2곳을 `tryGet`으로 바꿨다. adapter.out → `broker.application.service` 의존은 ArchUnit(inbound만 금지)·broker "application" NamedInterface가 허용한다. `CryptoMarketIndicatorService`의 2값 묶음 3곳은 지역 record로 원자성을 유지했다.

### CQ-07 로그 문맥 접두사 형식 혼재
- 주제: 로깅 형식. 유형: 비일관성.
- 위치:
  - 기준 형식 `[strategyId={}]`, 29건:
    - `CycleRotationService.java:48,63,92,95,98`
    - `VrCycleRolloverService.java:50,55,64,70,84,90,107,126,133,145,164`
    - `CyclePositionPersistor.java:63,72,99,102`
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
- 상태: done(cc6db1e64). trading-core·api의 userId/accountId/orderId/strategyId 본문 `key=` 로그를 접두사로 통일(`[{}]`는 orderId 등 명확한 경우만 앞에 접두사 추가). 알림·예외 문구 속 `orderId=`와 FinanceAccountService(금융 accountId라 trading accountId와 혼동)는 유지.
- 후속(2026-11-08): `[{}]`+닉네임 약 35줄을 `[accountId={}]`로 통일(trading `application/service`·matching·`TradingReportNotifier`). matching `PlanContext`의 `String label`은 `UUID accountId`로 교체(backtest는 null). 남긴 로그는 쓸 수 있는 strategyId/accountId/orderId/userId가 없는 경우다: `TradingService:461` lockName, `TradingPriceFetcher:192` ticker, 접두사 없는 `TradingService`·`TradingPriceFetcher`·`TradingPolicyService` 일부, `DcaPlanInputCollector:146` `[DCA]`, `StrategyStartAmountCalculator` `[PRIVACY]`. `BrokerCallGuard`·`SchedulerLockService`는 작업 라벨·잠금 키라 대상이 아니다. 예외 확정(2026-11-08): 접두사는 엔티티 id가 범위에 있을 때만 적용하고, 잠금·종목·정책 단위 로그는 그 키나 범주 태그(`[DCA]`·`[PRIVACY]`)를 그대로 쓴다. 로그 때문에 시그니처를 넓히지 않으며 `[ticker={}]` 같은 새 형식도 만들지 않는다.

### CQ-08 `CyclePosition.toBalance()`가 있는데 인라인 생성
- 주제: 매핑. 유형: 보일러플레이트.
- 위치 ✔: `trading/application/service/support/TradingBalanceLoader.java:29,37`. `new AccountBalance(h.holdings(), h.avgPrice(), h.usdDeposit())` 형태다.
- 기준 패턴: `trading/domain/model/CyclePosition.java:52` `toBalance()`. 이 메서드를 쓰는 곳은 `CryptoDcaRoundExecutor.java:113`, `CryptoLedgerRecorder.java:45`, `StrategyDetailAssembler.java:170`이다.
- 근거: 같은 3필드 변환이 이 파일에서만 인라인돼 있다.
- 예상 효과: 보일러플레이트 제거.
- 위험: 낮음.
- 가드: 없음(단발).
- 상태: done(5e1b51443).

### CQ-09 소유권 검증의 `verifyOwnedBy` 직접 호출 1곳
- 주제: 소유권 검증. 유형: 비일관성.
- 위치: `stats/application/service/InvestmentPointsQueryService.java:55`. `accountPort.findByIdOrThrow(...).verifyOwnedBy(userId)` 형태다.
- 기준 패턴: `AccountPort.requireOwnedAccount(accountId, requesterId)`. 33건/13파일이 이 방식이다.
- 근거: 결과(403)는 같고 호출 형태만 다르다.
- 예상 효과: 검증 경로 일원화.
- 위험: 낮음.
- 가드: 없음(단발).
- 상태: done(5e1b51443).

### CQ-10 `MeritzPriceApi` 다중 티커 루프 4벌
- 주제: 브로커 어댑터 중복. 유형: 보일러플레이트.
- 위치: `broker/adapter/out/meritz/MeritzPriceApi.java:73-95`. `getPrices`, `getPriceSnapshots`, `getPrevCloses`, `getClosingPrices`가 모두 `for (t : tickers) result.put(t, getX(t, account))` 형태다.
- 기준 패턴: 파일 안 private 헬퍼 하나로 묶는다. 예: `perTicker(tickers, t -> getX(t, account))`.
- 근거: 같은 루프가 한 파일에 4번 있다. 메리츠가 단건 API만 제공해서인지는 미확인이다. 그렇더라도 루프 모양이 같다.
- 예상 효과: 보일러플레이트 제거.
- 위험: 낮음.
- 가드: 없음.
- 상태: done(5e1b51443).

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
- 상태: done(cc6db1e64). `VrRampValidator.requireIntervalWeeks`로 3곳 통합. ReorderService·ReorderCommand는 코드에 이미 없어 해당 없음.

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
- 가드: CQ-05 테스트와 함께 `modules/api/web.md` "예외 매핑 SSOT"에 "부재=NoSuchElement, 상태=ISE, 입력=IAE" 한 줄을 추가한다.
- 상태: done. 활성 사이클·DCA 설정·주문·사이클 부재는 NSE, `ManualTradingService` 상태 위반은 ISE로 통일했다. kista-ui는 detail 문자열로 분기하지 않고(`apiMsg` 표시만), 상태코드 분기는 preview 404(`orderBannerCopy.ts:60`) 하나라 영향이 없다. `ReorderBuyBudgetService`·`ReorderService` 주문 부재는 400에서 404로 바뀐다.

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
- 가드: `KisHttpClientTest`·`TossHttpClientTest`에 "429/EGW00201 → BrokerRateLimitException" 단언을 넣었다. `constraints.md` "브로커 호출 실패 정책"에 HTTP 클라이언트 변환 규칙을 추가했다.
- 상태: done(2dc6cd156). KIS는 `KisHttpClient.httpFailure`(GET 백오프 재시도 소진 후, POST는 즉시), Toss는 `executeWithBackoffRetry`에서 변환한다. Toss `getCommon`의 429 재시도는 호출 람다 안(`retryOnTooManyRequests`)으로 옮겨 유지했다. `isRejection`이 이미 확정 거절로 보므로 주문 판정은 그대로다. 후속(e128a2bf4): `BrokerRouter.adminFirst`가 계좌 보충의 429를 관리자 부분 결과보다 우선해 전파하던 것을 일시 오류로 보고 부분 결과 우선으로 바꿨다(422만 항상 전파). Toss `getCommon` 백오프 중 인터럽트는 KIS와 같게 확정 거절 `TossApiException`(503)으로 던진다.

### CQ-15 전략 detail 저장의 타입 if/else 라우팅
- 주제: 전략 타입별 분기. 유형: OOP(capability 대체).
- 위치 ✔: `trading/application/service/StrategyCreationPersister.java:53-58,73-75`. `isInfinite`, `isVr`, `isDca` 순서로 if/else한다.
- 기준 패턴: `StrategyCreationResolvers.of(type)` 라우터(`CycleStrategyBeanConfig` 배선). 타입별 detail 저장을 resolver의 capability로 옮긴다.
- 근거: 등록 해석은 라우터를 쓰는데 저장만 타입 분기다. 같은 파일에서 두 번 분기한다. 전략 타입을 추가할 때 누락될 지점이다.
- 예상 효과: 타입 추가 시 수정 지점 축소.
- 위험: 중간. 등록 트랜잭션 경로를 건드린다.
- 가드: CQ-03의 규칙 문장이 덮는다.
- 상태: done(814529f7a). resolver로 옮기면 domain/creation이 포트를 써야 해 레이어를 깬다. 대신 타입별 detail 저장을 default 없는 switch 식으로 바꿔 타입 추가 시 컴파일 오류가 나게 했다.

### CQ-16 시드타입 강제 규칙이 capability 밖에 있음
- 주제: 전략 타입별 분기. 유형: OOP.
- 위치: `trading/application/service/StrategyCreationService.java:107,110`. VR과 DCA는 시드타입을 NONE으로 강제한다.
- 기준 패턴: `StrategyType.capability()`(`StrategyCapability`). 타입별 정적 정책은 여기에 둔다.
- 근거: 시드 정책이 capability에 없고 서비스 분기로만 존재한다(`StrategyCapability` 필드: `requiresPrivacyBase`, `supportsReverseMode`, `divisionCounts`, DCA 선택지).
- 예상 효과: 정책 SSOT 확보.
- 위험: 중간.
- 가드: CQ-03의 규칙 문장이 덮는다.
- 상태: done(814529f7a). `StrategyCapability.supportsCycleSeed`를 추가해 등록(NONE 강제)과 수정(거부)이 같은 규칙을 본다. 수정 경로가 VR만 거부해 DCA에 비-NONE 정책이 저장되던 불일치를 함께 고쳤다.

### CQ-17 현재 시각 취득 방식 혼재 (정적 now vs Clock 주입)
- 주제: 날짜·시간대. 유형: 비일관성. 과설계(이중 생성자)도 포함한다.
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
- 기준 패턴(확정, 사용자 2026-11-01): 한 번에 Clock 주입으로 통일한다.
  1. 앱 루트 `ClockConfig` 2곳(trading-core·root)에 `Clock` 빈(KST)을 둔다 — platform은 kernel `TimeZones` 의존 금지·`ZoneId.of` 가드 때문에 빈을 둘 수 없음(사용자 결정 2026-11-01). root·shared도 함께 통일.
  2. 서비스·어댑터·스케쥴러는 `X.now(clock)`을 쓴다. 이중 생성자 7곳은 생성자 하나로 정리한다.
  3. 도메인·정적 유틸(`DstInfo`, `StrategyCycle` 등)은 시각을 파라미터로 받는다. `DstInfo`의 인자 없는 메서드는 없앤다(호출 16곳).
  4. 테스트는 `Clock.fixed(...)`를 명시 주입한다. `@InjectMocks`가 `Clock`을 mock으로 채우면 `instant()`가 null이라 NPE가 난다.
- 근거: 정적 `now()` 호출 74곳/38파일(`grep -rnE '(LocalDate|Instant|ZonedDateTime|OffsetDateTime|LocalDateTime|LocalTime)\.now\(' trading-core/src/main/java`), 이 중 domain 3파일 ✔. `Clock` 빈은 없다 ✔. 그래서 Clock 사용 7곳이 기본 생성자(`Clock.system*`)와 테스트용 생성자를 이중으로 둔다.
- 예상 효과: 시각 취득 방식 단일화, 날짜 경계 테스트 결정성.
- 위험: 중간. 매매 배치 경로(`TradingService`, `OrderCancelService` 등)를 건드리지만 같은 시각을 다른 경로로 얻을 뿐 동작은 바뀌지 않는다. 생성자 변경이 테스트에 넓게 번진다.
- 가드(ArchUnit 초안, 루트 `HexagonalArchitectureTest`): trading-core 클래스가 인자 없는 `now()`(`LocalDate`, `LocalDateTime`, `LocalTime`, `Instant`, `ZonedDateTime`, `OffsetDateTime`)와 `X.now(ZoneId)`, `Clock.system*()`를 호출하지 못하게 한다. 예외는 Clock 빈 정의 클래스 한 곳뿐이다.
- 상태: done(d7e05294d). 가드 `HexagonalArchitectureTest.main_code_must_not_read_system_clock_directly`로 잠금.

### CQ-18 `TradingCycleResponse.forRegistration`의 15필드 재나열
- 주제: 매핑. 유형: 보일러플레이트.
- 위치: `trading/adapter/in/web/dto/TradingCycleResponse.java:115-117`. `from(detail)`의 결과를 15필드 나열로 다시 만들고 마지막 boolean 하나만 바꾼다.
- 기준 패턴: `with*()`. 이 record에는 `with*()`가 없다. `withX` 하나를 추가하거나 `from`에 인자를 넘긴다.
- 근거: 필드를 추가할 때마다 두 곳을 맞춰야 한다.
- 예상 효과: 보일러플레이트 제거.
- 위험: 낮음.
- 가드: 없음.
- 상태: done(5e1b51443).

### CQ-19 예외 로그의 스택 포함 여부 혼재
- 주제: 로깅 형식. 유형: 비일관성.
- 위치:
  - 전수 목록은 재현 명령으로 갈음한다: `grep -rnE 'log\.(warn|error)\(' trading-core/src/main/java`. 결과를 마지막 인자가 `e.getMessage()`인 줄과 `e`인 줄로 나눈다.
  - `e.getMessage()`만 남기는 곳 48건. 대표: `OrderSubmitter.java:47,78,97,115,149,164`, `OrderSubmissionReconciler.java:94,97,113`, `TradingPriceFetcher.java:178,192`, `ManualTradingService.java:81,118,144`.
  - 예외 객체를 넘겨 스택을 남기는 곳 28건. 대표: `CryptoMarketIndicatorService.java:119,135,153,211,257`, `TradingService.java:297`, `CycleRotationService.java:92`.
- 기준 패턴(확정, 사용자 2026-11-01): 레벨로 가른다. `log.error`는 항상 예외 객체를 마지막 인자로 넘겨 스택을 남긴다. `log.warn`은 `e.getMessage()`만 남긴다(예상된 실패: 브로커 거절, 시세 실패 후 대체값, 재시도 대상). 스택이 필요한 warn은 error로 올린다. `BrokerCallGuard.tryGet`의 `e.toString()`은 warn 폭주 방지 취지라 이 기준과 일치한다.
- 근거: warn·error 158건 중 위 분포.
- 예상 효과: 장애 분석 시 스택 유무를 예측할 수 있다.
- 위험: 낮음.
- 가드(`constraints.md` 주석 규칙 옆 문장 초안): "`log.error`는 예외 객체를 마지막 인자로 넘긴다. `log.warn`은 `e.getMessage()`만 남기고 스택이 필요하면 error로 올린다."
- 상태: done(cc6db1e64). error 6곳을 예외 객체 전달로, warn+스택 3곳(TossAuthApi·BrokerCallGuard·CryptoDcaRoundExecutor)을 메시지만으로. 스택 필요 가능성이 있는 warn 4곳(AccountTokenEvictionAdapter·KisPriceApi·MeritzPriceApi·CryptoOrderSynchronizer)은 유지. CryptoMarketIndicatorService는 이미 로그 없음.

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
    - 조회 폴백은 `.orElse(DEFAULT_DIVISION_COUNT)`다: `CyclePositionPersistor.java:92`, `CycleRotationService.java:79`, `StrategyDetailAssembler.java:50,130`, `CycleOrderComputer.java:132,136`.
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
- 상태: done(e6b1470f1). DTO 기본값 제거·`divisionCount` Integer(null=생략, 명시 0은 400)·VR `recurringAmount` 생략은 정책 기본 recurringMode(운영 DEPOSIT → 400)·`initialUsdDeposit` 정규화 `StrategyCreationService` 1곳·broker 기본값 `AccountService.brokerOrDefault`. 조회 경로 `.orElse(DEFAULT_DIVISION_COUNT)`는 유지 목록. 가드: `constraints.md` "persistence 기본값"의 web DTO 문장 + `StrategyServiceTest.register_infinite_explicitZeroDivisionCount_rejected` 등.

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
- 상태: done(5afbb0e38). 새 상수 대신 기존 SSOT `Currency.USD.amountScale()` 재사용, 원장 경로(코인 DCA 포함)는 `AccountBalance.LEDGER_SCALE`. 변이 확인으로 미커버 공식 site 8곳에 고정 테스트 추가. DCA 크립토 경로는 CQ-40. 가드: `constraints.md` "persistence 기본값" 절의 USD 금액 반올림 문장.

### CQ-21 백테스트 러너 선택의 타입 switch
- 주제: 전략 타입별 분기. 유형: OOP.
- 위치: `stats/domain/backtest/BacktestEngine.java:70-72,81` ✔. `switch (command.type())`로 `runVr`/`runInfinite`를 고르고, PRIVACY는 별도 오버로드다. 같은 클래스가 `CycleOrderStrategies`를 이미 쓴다(`:218,279,351`).
- 기준 패턴: `CycleOrderStrategies` capability.
- 근거: 주문 계산은 capability를 쓰는데, 러너 선택만 타입 switch다. `BacktestService.java:65,70,76,84,108,115`에도 타입 분기 6곳이 있다.
- 예상 효과: 백테스트 지원 타입을 추가할 때 수정 지점이 줄어든다.
- 위험: 높음. 백테스트 결과가 공식 계산을 재현한다.
- 가드: CQ-03의 규칙 문장이 덮는다.
- 상태: done(부분, 814529f7a). `BacktestService`의 기준표 조회 분기는 capability로 바꾸고, `BacktestEngine.run` 러너 선택은 default 없는 switch로 바꿔 타입 추가 시 컴파일 오류가 나게 했다. VR·INFINITE 전용 검증 분기는 타입 고유 파라미터 검증이라 유지한다.

### CQ-22 평단가 scale 4와 가격 scale 2 혼재
- 주제: 금액·수량 스케일. 유형: 비일관성.
- 위치:
  - 평단 4자리: `matching/domain/model/AccountBalance.java:82`, `VrReconfigureService.java:165`, `PresentBalanceResult.java:54`.
  - 가격 2자리: CQ-20 참조.
- 기준 패턴(확정, 사용자 2026-11-01): 평단은 2자리 HALF_UP으로 계산한다. `cycle_position.avg_price`가 `numeric(20,2)`(`V1__init.sql`, `CyclePositionEntity.java:38` `scale = 2` ✔)라 실제 계약이 이미 2자리다. 4자리는 저장 전 메모리에만 있다가 DB가 다시 반올림한다(이중 반올림).
- 근거: 같은 USD 단가가 경로에 따라 4자리 또는 2자리다. 이중 반올림은 드물게 1센트 차이를 낸다(예: 45.12449는 4자리 45.1245를 거쳐 저장 시 45.13, 직접 2자리면 45.12).
- 범위: `AccountBalance.java:82`, `VrReconfigureService.java:165`. `PresentBalanceResult.java:54`는 브로커 잔고 응답 변환이라 제외한다.
- 절차: (1) 위 경계값으로 현재 결과를 고정하는 테스트를 먼저 넣는다. (2) 2자리로 바꾸고 달라지는 케이스만 확인한다. (3) `trading-formulas.md`에 "averagePrice 2자리 HALF_UP"을 명시한다.
- 예상 효과: 원장 평단 표시 일관성.
- 위험: 높음. 평단은 INFINITE·VR 주문가 계산의 입력이다.
- 가드: `trading-formulas.md` 명시 + 경계값 단위 테스트.
- 상태: done(2532f2f3d). 범위에 `CapitalInjectionService:107`(주식 추가 평단, 4자리) 추가. 경계값 실례는 45.12495(4자리 45.1250 → 저장 45.13, 직접 45.12) — 45.12449는 두 경로 모두 45.12라 차이가 없다. 백테스트는 평단을 메모리에 누적해 infinite 골든 117행이 +0.03~0.04 바뀌었다(체결·사이클 수 불변). 가드: `trading-formulas.md` averagePrice 2자리 명시 + `AccountBalanceTest.avgPrice_roundsDirectlyToScale2`·`CapitalInjectionServiceTest`·`VrReconfigureServiceTest` 경계 테스트.

### CQ-23 재주문·배치 원장 예산이 장중 부분체결 후 취소된 BUY 체결분을 모름
- 주제: 원장 예산 정확도. 유형: 누락.
- 위치: `StrategyLedgerBudget.remaining`, `ReorderBuyBudgetService`의 refund 계산.
- 근거: 취소 시점 체결량을 DB가 모른다. 원본이 장중 부분체결 후 취소되고 재주문하면, 체결분은 마감 리포트 전까지 장부에 없어 원장 계획을 초과해 매수할 수 있다. 투자금 인출(`CapitalInjectionService.withdraw`)은 취소된 BUY 금액 전체를 상한으로 빼서 막았다.
- 수정 방향: 취소 시점에 브로커 체결 조회로 `filledQuantity`를 기록하고, 예산이 CANCELLED 주문의 기록된 체결분만 차감한다. 인출처럼 상한 방식을 쓰면 가격만 바꾸는 재주문이 원본 금액만큼 이중 차감돼 막히므로 부적합하다.
- 상태: done. 취소 확정 직후 체결 조회로 `filledQuantity`를 기록하고(`CancelledOrderFillRecorder`, fail-open), `StrategyLedgerBudget.remaining`·인출 가능액이 리포트 전 CANCELLED BUY의 기록된 체결분만(모르면 주문 금액 전체) 차감하며, 재주문 사전검사·`ReorderBuyBudgetService`는 원본의 현재 체결량을 조회해 환급분을 미체결분으로 계산한다.

### CQ-24 주식 DCA SELL 상한이 장중 취소된 SELL 수량 전체를 뺌
- 주제: DCA 판매가능수량 정확도. 유형: 비일관성.
- 위치: `DcaPlanInputCollector.cancelledPlacedSell`(주석 "취소 시점 체결량을 DB가 모르므로 상한으로 본다").
- 근거: CQ-23 이후 취소 확정 직후 체결분이 `filledQuantity`에 기록된다(조회 실패 시 null). BUY 원장 예산은 기록된 체결분만 빼는데 DCA SELL 상한은 여전히 취소된 SELL 수량 전체를 빼서, 가격만 바꾼 SELL 재주문이 원본 수량만큼 이중 차감된다.
- 수정 방향: `StrategyLedgerBudget.pendingCancelledFill`과 같은 정의(기록 체결분, null이면 전체)로 맞춘다. 리포트 전 날짜만 빼는지도 함께 확인한다.
- 상태: done. `DcaPlanInputCollector.cancelledPlacedSell`이 `StrategyLedgerBudget.pendingCancelledFill`과 같은 정의(기록된 `filledQuantity`, null이면 주문 수량 전체, `UNKNOWN_LEG` 제외)로 차감하고 마감 리포트 후 날짜(`isReported`)는 차감하지 않는다.

### CQ-25 일시정지 시 취소된 주문의 체결분이 원장에 반영되지 않음
- 주제: 원장 정확도. 유형: 누락(기존 동작).
- 위치: `StrategyService.pause` → `OrderCancelService.cancelOpenOrders`, `TradingService.reportAll`(활성 전략만 리포트).
- 근거: 정지된 전략은 마감 리포트를 타지 않아 정지 직전 장중 부분 체결분이 `cycle_position`에 들어가지 않는다. CQ-23 이후 CANCELLED 행에 체결이 기록돼 통계·VR `poolUsed`에는 보이지만 원장 보유·예수금과 어긋난다. 원장 예산은 오늘·다음 거래일만 보므로 다음 날부터 이 체결분이 빠진다.
- 수정 방향: 정지된 전략도 당일 취소 체결분이 있으면 리포트(포지션 반영)하거나, 재개 시 대사한다.
- 상태: done(7c4421e51). 마감 배치가 당일 접수 주문이 있는 정지 전략도 체결 리포트 전용으로 처리하고(청산 시 사이클 종료 `markEnded`만 기록하고 rotation·사이클 완료 알림·VR 롤오버는 하지 않는다 — 재개가 `reopenCycleIfEnded`로 새 사이클을 열고 VR 롤오버는 재개 후 따라잡는다) 재기동 리포트 재개에도 같은 규칙을 적용했다.

### CQ-26 Toss 체결 조회가 전날 저녁 체결을 당일 거래일에서 뺌
- 주제: 브로커 어댑터 날짜 기준. 유형: 버그 의심(기존 동작).
- 위치: `TossOrderApi.fetchExecutions`(`filledAt` KST 일자 재필터, `TossOrderApi.java:139-145` 부근).
- 근거: 거래일 D 주문이 D-1 22:00 이후 장중에 체결되면 `filledAt` 일자가 D-1이라 `getExecutions(D, D)`에서 빠진다. 리포트·취소 직후 체결 기록 모두 체결을 놓친다. Toss는 현재 대시보드 용도라 영향이 작다.
- 수정 방향: `filledAt`을 거래일 경계(`DstInfo.nextTradeDate`)로 변환해 필터한다. 실계좌 확인 필요.
- 상태: done(4f02195e5). broker는 trading `DstInfo`를 쓸 수 없어 04:30 경계 대신 KIS·메리츠와 같은 "US 세션일 + 1" 규칙을 택했다 — `filledAt`의 뉴욕 일자를 `UsTradeDates.toKstTradeDate`로 바꿔 필터하고 `TossOrderApi`를 UsTradeDates allowlist에 추가했다. 경계는 `TossOrderApiTest.getExecutions_tradeDateFromFilledAtNewYorkDate`가 잠근다. 실계좌 `filledAt` 실측은 미검증.

### CQ-27 투자금 추가·수동 보정 리포트 창 판정이 정지 전략의 리포트 비대상 주문까지 "리포트 전"으로 봄
- 주제: 운영 정합성. 유형: 비일관성(기존 동작, CQ-25로 악화되지 않음).
- 위치: `CapitalInjectionService.requireOutsideReportWindow`(`CapitalInjectionService.java:189-200`), `ManualTradeCorrectionService.rejectDuringReportWindow`(`ManualTradeCorrectionService.java:125-136`).
- 근거: 두 판정은 외부번호 없는 당일 주문(정지로 PLANNED만 취소된 경우)도 "리포트 전"으로 본다. 정지 전략 리포트 대상은 외부번호 보유 주문만이라, 그런 정지 전략은 마커가 안 찍혀 postClose~자정 투자금 추가·인출·수동 보정이 거절된다.
- 수정 방향: 창 판정에도 `externalOrderId != null` 조건을 맞추거나 `TradingService.reportableOrders`를 공용화한다.
- 상태: done(2ea707973). 마감 리포트 대상 판정을 `CloseReportScope.awaitsCloseReport`로 공용화해 마감 배치·투자금 추가/인출·수동 보정이 같은 기준(정지 전략은 접수 주문 보유 시만)을 쓴다.

### CQ-28 마감 접수 후~장마감 사이 재주문이 리포트에서 빠짐
- 주제: 원장 정확도·주문 안전. 유형: 버그(기존 동작). 위험: 높음(빈도 낮음).
- 위치: `TradingService.java:279-292`(리포트 `mainOrders` = 접수 시점 메모리 목록 + 그 시점 PLACED), `ReorderService.java:101-113,194-197`, `DstInfo.java:161-167`(IMMEDIATE 허용 창 개장 후~마감 전), `TradingReporter.java:65-68,99-101,138`.
- 근거: 마감 배치 접수(DST 04:30)와 장마감(05:00) 사이 IMMEDIATE 재주문은 `ownIds` 밖이라 체결이 원장에 반영되지 않고, 잔여 취소도 FILLED/CANCELLED 기록도 되지 않아 PLACED로 남는다. PRIVACY는 다음 날 보유 보정이 같은 수량을 다시 산다. 같은 창의 AT_CLOSE 재주문은 PLANNED로만 저장되고 원본만 취소된다.
- 수정 방향: 재주문 허용 창을 마감 배치 접수 전까지로 좁히거나, 리포트가 당일 PLACED를 리포트 시점에 다시 조회한다. 설계는 brainstorming부터 한다.
- 상태: 해소(2026-11-04) — 재검증 결과 기술한 경로는 재현되지 않았다(자정 이후 DstInfo.calculate의 marketOpen이 당일 22:30이라 IMMEDIATE가 닫혀 있고, 04:30 이후 재주문은 nextTradeDate 검사로 다음 거래일 주문이 된다). 대신 자정~장마감 IMMEDIATE 차단·AT_OPEN 허용 판정 오류와 원본 거래일 미검사를 찾았고, 운영 사용 0건이라 관리자 재주문 기능 자체를 제거했다.

### CQ-29 마커 커밋 뒤 `markEnded` 실패 시 사이클이 영구히 닫히지 않음
- 주제: 사이클 수명. 유형: 버그(기존 동작). 위험: 중간.
- 위치: `CyclePositionPersistor.java:57-63`(포지션·상세·리포트 마커 한 트랜잭션) 대 `:71-85`(트랜잭션 밖 `markEnded`·`rotate`).
- 범위: `markEnded` 앞 실패만 해당(`markEnded` 성공 후 `rotate` 실패는 `BatchContextFactory` 좀비 판정이 받음).
- 근거: 커밋 뒤 DB 오류나 SIGKILL이 나면 재개는 마커를 보고 건너뛰고, 다음 날 최신 holdings=0이라 `prevHadHoldings=false`가 되어 종료·회전이 다시 일어나지 않는다. NONE seed INFINITE면 PAUSED되지 않고 0회차 매수를 이어 간다.
- 수정 방향: `markEnded`를 마커와 같은 트랜잭션에 넣는다(`rotate`는 알림·이벤트 포함이라 트랜잭션 밖 유지).
- 상태: done(2026-11-06) — `CyclePositionPersistor`가 `markEnded`를 마커 트랜잭션 안에서 실행, `CyclePositionPersistorDbTest.saveCyclePosition_rollsBackPositionAndMarker_whenMarkEndedFails`가 잠금.

### CQ-30 INFINITE 리버스모드 쿼터매수 BUY에 cap 미적용
- 주제: 주문 안전. 유형: 누락(기존 동작). 위험: 중간(매매 공식 인접 — 높음 규칙 적용).
- 위치: `InfiniteCycleOrderStrategy.java:141`(`OrderPlan(null, …)`), `:155`, `:165-167`, `BuyOrderPriceCapper.java:54`.
- 근거: 리버스모드는 position이 null이라 `needsCapCheck`가 false이고 `capBuyOrders`가 원본을 돌려준다. 예: 별지점 30.00, 현재가 27.00이면 cap 28.35인데 29.99 LOC가 그대로 접수된다. `workflow-capper.md`에 리버스 제외 기술이 없다. Toss CLS 범위 초과 취소 여부는 미확인.
- 수정 방향: 리버스 BUY도 cap 대상에 넣거나, 제외가 의도라면 `workflow-capper.md`에 이유를 기록한다.
- 상태: done(2026-11-06) — 사용자 확인: cap 이유는 LOC 지정가·종가 괴리 15~20%에서의 증권사 주문 실수 거부이고, 리버스 쿼터매수(별지점 = 5거래일 평균)는 급락 구간에서 오히려 괴리가 크다. 일반모드와 같은 예산 보존 방식으로 가격 cap + 수량 = 쿼터 예산 ÷ cap(`InfiniteCycleOrderStrategy.capBuyOrders`, `ReverseModePosition.quotaBuyQuantity`). 리버스 plan이 cap용 position을 싣고 `OrderPlan.reverseMode`로 미리보기 표시만 가린다. 백테스트도 운영 `capBuyOrders`를 호출하도록 맞췄다. 회귀: `BuyOrderPriceCapperTest.reverseQuotaBuy_exceedingCap_capsPriceAndRecomputesQuantity`(변이 확인 완료).

### CQ-31 재캡이 이미 PLACED된 평단가 매수①을 재생성
- 주제: 주문 안전·멱등성. 유형: 버그(재실행 전제). 위험: 낮음~중간.
- 위치: `BuyOrderPriceCapper.java:72,149-155`(PLANNED BUY만 조회), `InfiniteStrategy.java:92-97`.
- 근거: AVG는 PLACED이고 REF·보정만 PLANNED인 상태에서 재조회 가격 하락으로 cap이 걸리면 `computeEarlyBuys`가 buy1=null로 보고 `INFINITE_EARLY_AVG_BUY`를 새로 저장한다. `savePlannedOrders`는 중복 슬롯을 거르지 않고 orders에 UNIQUE 제약도 없다.
- 수정 방향: 재캡 입력에 PLACED leg를 반영하거나, 같은 사이클·거래일·leg 중복 저장을 막는다.
- 상태: done(2026-11-06) — 코드상 중복 경로 확정, 트리거는 접수 도중 인터럽트/크래시 후 재개. `applyBatchCap`이 재저장 직전 `findPlannedOrPlacedByCycleAndDate`(취소 대상 제외)로 `TradingOrderSlots.excludeExisting`을 적용. 회귀: `BuyOrderPriceCapperTest.capIfNeeded_avgLegAlreadyPlaced_doesNotResaveAvgLeg`(변이 확인 완료).

### CQ-32 PRIVACY 실거래 기준표 조회에 거래일 정확 일치 가드 없음
- 주제: 날짜 기준. 유형: 비일관성(기존 동작). 위험: 낮음.
- 위치: `PrivacyTradePersistenceAdapter.java:138`(`>=` ASC 첫 건), `TradingPriceFetcher.java:82`, `StrategyOrderPlanBuilder.java:71`. 대비: `BacktestService.java:183-187`은 `appliesTo`로 거른다.
- 근거: D-1 발행분이 없고 그 뒤 발행분이 이미 있으면 PLANNED가 미래 거래일로 저장돼 당일 접수에서 빠진다. 운영은 수신이 07:00경이라 04:30 배치에 미래분이 거의 없다.
- 수정 방향: 실거래 경로도 거래일 일치를 검사한다.
- 상태: done(2026-11-06) — `findTodayTrade`를 `release_date = releaseDateFor(today)` 정확 일치(`findWithOrdersByReleaseDateAndTicker`)로 바꾸고 BacktestService `appliesTo` 필터를 제거했다. 근거: 운영 PRIVACY 78개 거래일 모두 D-1 기준표가 있어 결과 동일, `>=`(910f20dc8)의 주말 월요일 매매표 인식 의도는 월요일 발행이 04:30 이후라 실거래·미리보기에 실익이 없다. 기준표가 없으면 empty → 기존 NO_PRIVACY_BASE skip 경로.

### CQ-33 통계·원장 경계의 낮은 우선순위 정합성 묶음
- 주제: 통계 정확도. 유형: 정의·경계(기존 동작). 위험: 낮음.
- 위치·근거:
  - VR 롤오버 지연 구멍: `VrCycleRolloverService.java:145`가 `markEnded(evaluationDate)`로 닫으므로, 재시도로 today > evaluationDate면 그 사이 날짜에는 옛 사이클이 endDate 규칙으로 빠지고 새 사이클 스냅샷은 아직 없어 전략이 곡선에서 빠진다(`TradingStatsService.buildPoints`). (해소 2026-11-07: 938206ff3)
  - VR 종료금액 시점 혼합: due일 종가에 오늘 매매 후 holdings를 곱한다(`VrCycleRolloverService.java:119-126`). 지연 시 그 사이 체결이 옛 사이클 평가에 섞인다. (문서화로 종결 2026-11-07: ponytail 주석)
  - 본전 사이클이 패배로 집계: 승률은 `realizedPnl().signum() > 0`만 승으로 센다(`TradingStatsService.toTypeStats`). (해소 2026-11-07: 938206ff3)
  - 수동 체결 보정이 과거 trade_date로 SELL을 넣으면 DCA `withdrawnOn`(trade_date 기준)과 스냅샷(createdAt 기준)이 어긋나 그 사이 곡선 자산이 매도대금만큼 부푼다(`CycleView` `ponytail:` 주석). (종결 2026-11-07: withdrawnOn 삭제(131b749b0)로 근거 소멸. 남은 한계 — 보정분은 곡선에 보정 실행일로 반영)
  - 곡선 체인 보정의 surplus가 후속 사이클의 현재 startAmount를 쓴다(`TradingStatsService.chainAdjustment`). 회전 뒤 투자금 추가·인출이나 시드 수정이 있으면 자산선과 원금선이 같은 양만큼 함께 움직인다. 손익선은 맞다. (해소 2026-11-06: 개시 시점 시작금액 `startAmountAtOpen` 사용, 083ce5faa)
  - 종료 처리가 안 된 선행 사이클(CQ-29 상황)은 체인 보정에서 빠지고 후속 사이클 제외 규칙으로만 사라져 손익이 끊긴다. CQ-29를 고치면 해소된다. (해소: CQ-29 done 2026-11-06 — markEnded가 마커 트랜잭션 안에서 실행돼 미종료 선행 사이클이 생기지 않음)
  - `TtlResultCache.getOrCompute`에 세대(버전) 검사가 없다. 무효화 직전에 계산을 시작한 스레드가 무효화 뒤 옛 결과를 다시 넣으면 5분 TTL 동안 유지된다. TradingReportReady 무효화도 같다. (해소 2026-11-07: 938206ff3)
  - 전략 등록(`StrategyService.register`), 계좌 삭제 캐스케이드(`AccountCascadeListener`), 크립토 원장 스냅샷(`CryptoLedgerRecorder`)은 `StrategyCapitalChangedEvent`를 발행하지 않아 최대 5분 동안 옛 값이 보인다. (해소 2026-11-07: 938206ff3)
  - PRIVACY 보유 초과분은 BUY 감액으로만 흡수되고 매도로 따라잡지 않는다(`PrivacyStrategy.java:207-227`). 테스트로 고정된 동작이지만 문서화되지 않았다. (문서화로 종결 2026-11-07: privacy.md)
- 수정 방향: 항목별로 정의를 정한 뒤 처리한다. PRIVACY 초과분은 의도 확인 후 `modules/tradingcore/privacy.md`에 기록한다.
- 상태: done(2026-11-07, 938206ff3). CQ-29 의존 항목은 CQ-29 해소로 함께 종결.

### CQ-34 PRIVACY·INFINITE 경계 테스트 누락
- 주제: 테스트 고정. 유형: 누락. 위험: 중간(공식 인접).
- 위치·근거: 아래 경계 테스트가 없다.
  - priceOffsetRate 반올림 경계(currentRound 9.7~10)
  - divisionCount 30·40에서의 INFINITE 수량과 리버스 매도 수량
  - 리버스 BUY가 cap을 넘는 경우
  - PRIVACY 기준표 거래일 ≠ 배치 거래일
  - `CyclePositionPersistor`에서 마커 커밋 뒤 `markEnded`·`rotate`가 예외를 던지는 경우
  - 재캡 입력이 부분 PLANNED(AVG PLACED)인 경우
- 수정 방향: CQ-29~32를 고치면서 해당 테스트를 함께 넣는다. 반올림 경계는 단독으로 먼저 고정할 수 있다.
- 상태: done(2026-11-06) — 테스트만 추가(프로덕션 변경 없음).
  - 반올림 경계: `InfinitePositionTest.priceOffsetRate_roundingBoundary_20div`(9.70/9.75/9.76/10.00/11.00, 9.75가 `HALF_UP`→`HALF_EVEN` 변이를 잡음 확인).
  - 30·40분할: `InfinitePositionTest.priceOffsetRate_zeroBoundary_movesWithDivisionCount`·`isFinalRound_boundary_30_40div`·`quantities_30div`·`quantities_40div`, `ReverseModePositionTest.sellQuantity_dividesByHalfDivisionCount`. `InfinitePosition.isFinalRound` 주석의 "⟺ currentRound > 19"는 20분할 전용 표현(실제 경계는 div−1) — 주석만 어긋남, 다음 InfinitePosition 수정 때 고친다.
  - 리버스 BUY cap 초과: CQ-30에서 cap 적용으로 바꾸며 `BuyOrderPriceCapperTest.reverseQuotaBuy_exceedingCap_capsPriceAndRecomputesQuantity`·`reverseQuotaBuy_withinCap_unchanged`로 대체.
  - PRIVACY 기준표 날짜 ≠ 거래일: `PrivacyTradeBaseJpaRepositoryDbTest`(실 DB, T−2·T만 있으면 T−1 조회 empty). `(release_date, ticker)` UNIQUE 제약 있음(trading V1).
  - 마커 커밋 뒤 `markEnded` 예외: CQ-29의 `CyclePositionPersistorDbTest.saveCyclePosition_rollsBackPositionAndMarker_whenMarkEndedFails`. `rotate` 예외: `saveCyclePosition_keepsCommittedEndAndMarker_whenRotateThrowsAfterCommit_cq38`(원장·마커 보존, PAUSE는 `CycleRotationServiceTest`가 고정 — CQ-38).
  - 재캡 부분 PLANNED: CQ-31의 `BuyOrderPriceCapperTest.capIfNeeded_avgLegAlreadyPlaced_doesNotResaveAvgLeg`.

### CQ-35 투자금 추가·인출 이력이 남지 않아 과거 원금선이 소급 변경됨
- 주제: 통계 정확도. 유형: 누락(기존 동작). 위험: 중간(마이그레이션 필요).
- 위치: `CapitalInjectionService.java:96,144`(`updateStartAmount`만 호출, 이력은 `log.info`만), `TradingStatsService.CycleView.principalOn`(시드 점유 전략은 날짜와 무관하게 현재 `startAmount`), `:95,143`(`updateBalance(latest.id())`로 최신 스냅샷을 제자리 수정).
- 근거: 06-01 시드 10,000, 06-20 5,000 추가 → 06-01~06-19 원금선이 15,000이 되어 과거가 −33%로 보인다. 정지 전략은 최신 행이 며칠 전이라 과거 자산값도 바뀐다. 이력 테이블·이벤트·감사 기록이 없다(trading-core V1~V13 확인).
- 수정 방향: `cycle_capital_events(cycle_id, trade_date, amount)` 같은 이력 테이블을 만들어 일자별 원금 시계열(NavigableMap)로 바꾸고, 스냅샷을 제자리 수정하지 않고 새 행을 추가한다. 과거분은 복원할 수 없으니 운영 로그로 백필할지 포기할지 정한다. CQ-33의 체인 surplus 문제도 함께 해소한다.
- 상태: done(2026-11-06) — `cycle_capital_event` 이력 테이블(V15)과 포트(716266c24), 추가·인출 기록·이력 있는 사이클 시드 수정 거절(39b164a13), 이력 복원 헬퍼(530fd147d), 곡선 과거 원금·자산 고정(083ce5faa). 과거분 백필은 하지 않는다(이력은 배포 이후부터).

### CQ-36 VR "실현손익"에 팔지 않은 평가익이 포함됨
- 주제: 통계 정의. 유형: 정의 문제(기존 동작). 위험: 중간.
- 위치: `VrCycleRolloverService.java:137-145`, `CycleSnapshotCreator.java:94`(종료금액 = 예수금 + holdings × 종가), `TradingStatsService.CycleView.realizedPnl`.
- 근거: 롤오버 때 보유분은 팔리지 않고 승계된다(avgPrice 승계, `CycleSnapshotCreator.java:67`). 그런데 endAmount가 시가 평가라 종료 사이클 realizedPnl이 미실현분을 포함하고, 승률·평균 수익률은 N주 구간 수익률이 된다. 총손익(실현 + 미실현)은 맞다.
- 수정 방향(사용자 결정 대기): (a) UI 라벨만 "구간 손익"으로 바꾼다. (b) 근사값: Σ(SELL 체결금액 − 직전 스냅샷 avgPrice × SELL 수량)를 orders·cycle_position만으로 조회한다. (c) 정확한 값: 매도 체결 시 사이클별 `realized_pnl`을 기록한다(같은 날 매수·매도 순서, 수동 보정, 투자금 추가의 avgPrice 제자리 수정까지 반영하려면 필요).
- 상태: (a) done(kista-ui 39e4c77e — 라벨 "종료 사이클 손익"·"종료 손익", 보조문구 "VR은 롤오버 평가액 기준"; VR 외 전략은 실제 실현값이라 "구간 손익" 대신 이 라벨 유지). (c) done(2026-11-06, 083ce5faa) — 별도 realized_pnl 기록 대신 summary 실현손익을 종료 사이클 기준으로 재정의: 마지막 holdings 0이면 end−start, holdings>0(VR 롤오버·재설정)이면 장부가 기준 실현분, 미실현 = 총손익 − 실현. 승률·평균 수익률·성과 목록은 불변.

### CQ-37 전략 삭제 시 과거 성과가 통계에서 소급 소멸
- 주제: 통계 정책. 유형: 기능 공백(기존 동작). 위험: 중간(스케쥴러 판정 영향).
- 위치: `StrategyEntity.java:22`(`@SQLRestriction`), `StrategyPersistenceAdapter.java:80`(`findAllByAccountIdIn`), `StrategyStatus`(ACTIVE·PAUSED만 있음).
- 근거: 소프트 삭제된 전략의 사이클·스냅샷이 실현손익·승률·곡선에서 빠진다. 사용자는 "보고 싶어서 남길 것"과 "치우려고 지운 것"을 구분하고 싶어 한다.
- 수정 방향(사용자 추천안 동의): `StrategyStatus.ARCHIVED`(보관)를 신설한다. 보관하면 매매를 멈추고 목록에서 숨기되 통계는 유지하고, 삭제는 지금처럼 통계에서도 뺀다. 스케쥴러·상태 판정의 `!= PAUSED` 형태를 전수 점검한다(보관 전략이 매매되면 안 된다). kista-ui에 보관 버튼·보관함 목록·삭제 확인 문구를 추가한다.
- 상태: done(2026-11-07, 8795996a0). UI 후속(보관 버튼·통계 화면 보관 섹션·`includeArchived`)도 done(kista-ui ad7a49bc·0fa94063).

### CQ-38 마커 커밋 뒤 `rotate` 실패 시 ACTIVE 전략이 영구 skip됨
- 주제: 배치 복구. 유형: 버그(CQ-29 계열). 위험: 중간(발생 시 매매 정지, 자동 복구 없음).
- 위치: `CyclePositionPersistor.saveCyclePosition`(position·`markEnded`·`markReported` 커밋 뒤 `CycleCompletedEvent` 발행 → `cycleRotationService.rotate`), `StrategyService.resume`(`isActive()`면 거부, `reopenCycleIfEnded`는 재개 경로에서만 호출).
- 근거: `rotate`가 예외를 던지면 사이클 종료·리포트 마커는 이미 커밋돼 재개가 건너뛴다. 전략은 ACTIVE인데 최신 사이클이 종료된 상태라 매 배치 좀비 사이클로 skip된다. 사용자가 정지→재개해야 `reopenCycleIfEnded`로 풀린다. 완료 이벤트도 이미 발행돼 사용자에게는 정상 순환처럼 보인다. 고정: `CyclePositionPersistorDbTest.saveCyclePosition_keepsCommittedEndAndMarker_whenRotateThrowsAfterCommit_cq38`.
- 운영 이력(2026-11-06 조회): 최신 사이클이 종료된 전략 0건(상태 무관), `app_error_logs`(2026-06-26~)의 좀비·재등록 실패 0건, 종료→다음 사이클 공백 >1일 0건.
- 원인 범위: rotate에는 외부 HTTP가 없고, 생성 블록은 이미 실패 시 PAUSE했다. ACTIVE 좀비는 그 try 밖 실패에서만 생겼다 — persistor가 rotate 직전에 트랜잭션 밖에서 발행한 `CycleCompletedEvent`의 인라인 리스너(`requireProfile` 예외 시 rotate 미호출), `calcLastPositionDeposit` 읽기, NONE·1주 미만 분기의 pause.
- 기각: (a) 배치 시작 시 재시도는 price·privacyBase가 필요하고 진입점이 늘어 과하다. (b) 마커 트랜잭션 통합은 rotate 실패 시 청산 스냅샷(매도 후 원장)까지 롤백해 다음 배치가 매도 전 원장으로 매매한다. (c) 관리자 알림은 `BatchContextFactory`에 이미 있다.
- 해소: `CycleCompletedEvent` 발행을 rotate의 try 맨 앞으로 옮기고 try를 메서드 전체로 넓혔다. 어느 단계든 실패하면 PAUSE 후 rethrow하고, PAUSED + 종료 사이클은 `reopenCycleIfEnded`가 처리한다. 고정: `CycleRotationServiceTest`(리스너·원장 조회 실패 시 PAUSE, 완료→새 사이클 알림 순서), `CyclePositionPersistorDbTest..._cq38`(원장·마커 보존).
- 후속(2026-11-06): PAUSE 뒤 `CycleRotationFailedEvent`로 사용자에게 정지·재개 안내를 보낸다(관리자는 `TradingBatchGuard`). NONE·1주 미만 정책으로 이미 PAUSE한 뒤의 실패는 재정지·안내 없이 전파한다. PAUSE·안내 실패는 원래 예외의 suppressed로 남긴다.
- 남은 한계(수용): 리스너 외 실패면 완료 알림은 이미 나간 뒤다.
- 상태: done(2026-11-06).

## 유지 목록 (재제안 금지, 이유 기록)

| 발견 | 위치 | 유지 이유 |
|---|---|---|
| `CycleHistoryResponse`/`CycleHistoryPageResponse` trading·stats 완전 복제 | `trading/adapter/in/web/dto/`, `stats/adapter/in/web/dto/` (package 줄 외 diff 없음 ✔) | `own-type-ledger.md`가 (b) 외부 계약 분리로 허용한 항목이다. 엔드포인트별 응답 스키마. |
| `TossPriceApi`의 DstInfo 세션 계산 복제 | `broker/adapter/out/toss/TossPriceApi.java:161-199` ✔ | broker는 trading 도메인을 참조할 수 없다. 주석이 이유와 "common 승격 대상 아님"을 명시한다. NY 상수만 CQ-01에서 처리한다. |
| `TradingBatchResumer` 04:30 경계 주석 | `trading/adapter/in/schedule/TradingBatchResumer.java:84` ✔ | 요일 조합 판정이라 `nextTradeDate(ZonedDateTime)`와 계산이 다르다. 경계값 문서화 주석이다. |
| `MarketSessionSnapshot` 세션 계산 | `marketcalendar/domain/model/MarketSessionSnapshot.java:11,16` | marketcalendar는 trading을 참조하지 않는다. NY 상수만 CQ-01. |
| `TradingService` 배치 `LocalDate.now(KST)` | `TradingService.java:79,158` ✔ | 체크포인트 키(같은 날짜 수동 실행 DONE과 비교)라 거래일 값이 아니다. 주석에 명시돼 있다. |
| `CycleSnapshotCreator` `LocalDate.now(KST)` | `CycleSnapshotCreator.java:97` | 주석에 "r.today()(다음 거래일)와 다르다"고 명시돼 있다. |
| `DcaSummaryAssembler` `plusDays(1)` | `DcaSummaryAssembler.java:58-62` ✔ | KST 안에서 다음 실행 후보일을 계산한다. 기준 변환(KST↔US, 발행일↔거래일)이 아니다. |
| 조회 범위 `minusDays`/`plusDays` | `KisTradingApi:174`, `MeritzOrderApi:156-157`, `TradingCycleController:224`, `UpbitExchangeAdapter:118` 등 | 단순 조회 창이다. 기준 변환이 아니다. |
| stats 크립토 `ZoneOffset.UTC` | `CoinMarketCapAdapter`, `CoinbaseMarketDataAdapter`, `MempoolBlockHeightAdapter`, `BinanceMarketDataAdapter`, `CryptoPremiums:81`, `CryptoMarketIndicatorService:110,159,248` | 외부 원본(UTC 일봉)을 그대로 유지한다(`modules/shared/platform.md` "외부 원본 참조 데이터"). Toss 캔들 커서(`TossPriceApi.java:57,99`)가 UTC인 것이 API 요구인지는 미확인이다. |
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
| 주문 상태 판정 서비스 산재 | `OrderCancelService:60,159,163,177,185`, `DcaPlanInputCollector:157`, `CryptoOrderSynchronizer:64,70`, `OrderSubmissionReconciler:86`, `TradingReporter:102`, `TradingPreviewService:197` | `PLANNED \|\| PLACED` 조합은 재주문 제거(2026-11-04)로 서비스에 남지 않았고, 나머지는 조합이 제각각이다. 같은 이유로 바뀌는 3곳 이상이 없다. 같은 조합이 3곳이 되면 `Order` 판정 메서드로 올린다. |
| `TradingErrorEvent(null, ...)` 29곳 | 13파일 ✔ | 생성자 한 줄이라 헬퍼로 묶어도 이득이 없다. |
| `VrCycleRolloverService` 등 `e.getMessage()`만 보내는 알림 | `VrCycleRolloverService:51,86`, `VrReconfigureService:118` | 문맥 보강은 CQ-07·CQ-19의 기준이 정해지면 함께 본다. 별도 항목으로 두지 않는다. |
| 소유권 응답 코드 400/404/403 | `SelectionChain.java:27-41`, `StrategyStatusInternalController.java:34`, `TradingInternalQueryController.java:90-95` | `SelectionChain` 호출처는 `ManualTradeCorrectionService`뿐이다 ✔(재주문 제거, 2026-11-04). 내부 토큰 경로(root admin 대리 호출)로, userId가 바디로 들어와 입력 오류(400)가 맞다. 소유권 중복 제거는 기각 확정(2026-07). |
| `TradingStatsService`·`PortfolioService` 필터 방식 | `TradingStatsService.java:148-151`, `PortfolioService.java:25,33` | userId 범위로 조회해 남의 accountId는 빈 결과가 된다(주석 명시). 검증 누락이 아니다. |
| 내부 컨트롤러 소유권 검증 없음 | `PrivacyBaseInternalController`, `PrivacyInternalQueryController`, `FidaOrderController`, `AccountInternalController` | `/api/internal/**` X-Internal-Token 경로라 설계상 의도다. |
| `TradingCycleController` update에 `@Valid` 없음 | `TradingCycleController.java:92` | 같은 DTO의 `@NotNull type`(`TradingCycleRequest.java:18-19` ✔, "등록 시 필수")이 수정 요청에는 맞지 않는다. |
| `AccountController` 이름 변경에 `@Valid` 없음 | `AccountController.java:68` | 등록 전용 제약(@Pattern 계좌번호) 때문이며 주석에 명시돼 있다. 서비스가 별명만 수동 검증한다. |
| `DcaConfigRequest` 등 DTO compact constructor의 IAE | `DcaConfigRequest.java:17-18`, `DcaRuleRequest.java:34` | "[field: message]" 형식 대신 사용자 문구로 400을 내려는 의도이며 주석(`:16`)에 있다. |
| `UserNotifyProfilePersistenceAdapter` `occurredAt` 폴백 | `UserNotifyProfilePersistenceAdapter.java:86` | 이벤트 필드 주석이 "null이면 구버전 발행분"이라고 명시한다 ✔(`shared/kernel/UserNotifyProfileChangedEvent.java:18`). 레거시 nullable 예외다. |
| persistence SUM null → 0 | `OrderPersistenceAdapter.java:216,251` | 매퍼가 아니라 집계 쿼리 결과 처리다. |
| `BrokerCallGuard` 밖 raw catch(이벤트·분기 있음) | `ManualTradingService:80,117`, `OrderSubmitter:66`, `OrderSubmissionReconciler:92,112`, `CryptoDcaRoundExecutor:135,201`, `CryptoDcaRunner:79,94`, `TradingService:295,399`, `TradingBatchGuard:32`, `VrCycleRolloverService:49,83`, `TradingReporter:115`, `TradingOrderExecutor:176`, `CycleRotationService:87`, `PrivacyBaseGuard:32`, `BrokerRouter:138-142` | `constraints.md`가 허용하는 이벤트 발행·분기 처리 예외다. |
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
| `isCryptoExchange()` 서비스 분기 | `BatchContextFactory:51`, `OrderCancelService:57,93,159`, `CryptoDcaRunner:62`, `StrategyCreationService:73`, `TradingPreviewService:71,96`, `CryptoPortfolioService:76` | 전략 타입이 아니라 증권사 capability 술어다. 이미 capability 형태다. |
| `BootstrapPosition`·`BacktestEngine` 평단 필수 규칙 | `BootstrapPosition.java:35`, `BacktestEngine.java:420` | 2곳. |
| "사용자를 찾을 수 없습니다" 복제 | `BatchContextFactory.java:71`, `CryptoDcaRunner.java:122` | 2곳. |
| `String accountNo` 원시값 | 어댑터 9개 외 | 포트·어댑터 경계의 wire 값이다. 애그리게이트 내부는 `BrokerAccountRef`가 이미 묶는다. |
| 원장↔증권사 보유 수량 자동 대사 없음 | `TradingReporter` 마감 리포트 | 사용자가 전략과 별개로 같은 계좌에서 직접 매매할 수 있어 차이가 정상이다(사용자 결정 2026-11-07, CQ-39). 감지는 미리보기 `accountHoldingsCheck` 경고로 충분하다. |
| 조회 경로 `.orElse(DEFAULT_DIVISION_COUNT)` 폴백 | `CyclePositionPersistor`, `CycleRotationService`, `StrategyDetailAssembler`, `CycleOrderComputer`, `InfiniteCycleOrderStrategy` | null 기본값이 아니라 버전 상세 행 누락 방어다. 예외로 바꾸면 매매가 멈춘다(CQ-12, 2026-11-07). |
| 시드 미리보기 `divisionCount` `@RequestParam(defaultValue = "20")` | `TradingCycleController` 시드 미리보기 | 등록 resolver로 흐르지 않는 미리보기 전용 쿼리 파라미터다(CQ-12). |
| `CryptoLedgerRecorder` 증분 `divide(volume, 8, HALF_UP)` | `trading/application/service/` `CryptoLedgerRecorder` (코드 주석 있음) | 증분을 `LEDGER_SCALE`(2)로 먼저 반올림하면 경계에서 최종 예수금이 0.01 어긋난다. DB `numeric(20,2)`가 최종 합을 한 번 반올림하는 현행이 동작 보존이다(2026-11-08). |
| 잠금·종목·정책 단위 로그의 비표준 접두사 | `TradingService:461` lockName, `TradingPriceFetcher:192` ticker, `DcaPlanInputCollector:145` `[DCA]`(전략 간 공유 일봉 캐시 안), `StrategyStartAmountCalculator:43` `[PRIVACY]`, `TradingPolicyService` | 쓸 수 있는 strategyId·accountId·orderId·userId가 범위에 없다. 잠금 하나가 전략 여러 개를 덮어 id 하나를 넘기면 오해를 부른다(CQ-07, 2026-11-08). |
| 미사용 import 빌드 가드 | 빌드 설정(Checkstyle·Spotless·Error Prone 없음) | import는 바이트코드에 남지 않아 ArchUnit·`-Xlint`로 못 잡고, 가드는 신규 플러그인이 필요하다. 약 1,100개 파일 중 21개이고 타입 이동 뒤 남은 잔재라 재발 근거가 없다. 재검토 트리거는 이관과 무관한 재발이 감사에서 확인될 때(CQ-42, 2026-11-08). |
| `StrategyStatusInternalController` ARCHIVED 이중 검사 | `trading/adapter/in/web/StrategyStatusInternalController.java:37,48` | 선검사를 지우면 이미 ARCHIVED인 전략에 ARCHIVED를 요청할 때 no-op 200이 돼 400에서 바뀐다. switch의 `case ARCHIVED`는 default 없는 switch 컴파일 가드라 필요하다(CQ 감사 2026-11-08). |
| not-found ISE→NoSuchElementException 일괄 교체 | `StrategyCyclePersistenceAdapter`·`OrderPersistenceAdapter`·`CyclePositionPersistenceAdapter` | ISE는 400, NSEE는 404라 상태코드가 바뀐다. 2026-11-08 감사에서 타입은 유지하고 메시지만 한국어 고정 문구로 바꿨다. |
| `OrderPort` default `findByIdOrThrow` 부재 | `trading/application/port/output/OrderPort.java`, `CryptoLedgerRecorder:39`·`OrderCancelService:162`·`SubmittingOrderAwaiter:50` | 효과가 서비스 3곳 문구 통일뿐이고, default 메서드는 mock이 override해 테스트의 findById stub이 NPE가 된다(testing.md). 유지. |
| 응답 DTO enum 표현 혼재(String vs enum) | `TradingCycleResponse`·`ExecuteOrdersResponse` vs `DcaResponse`, `NextOrdersResponse`는 파일 안에서 `OrderItem`(enum)과 `TodayOrderItem`(String)이 섞임 | JSON 결과가 같고 통일하면 OpenAPI 스키마(api-types)만 흔들린다. 유지. |
| `UserEventRepublisher` 동일 본문 메서드 2개 | `trading/adapter/in/redis/UserEventRepublisher.java` | 2곳이라 추출 기준(3곳) 미달. 유지. |
| 코인 부분체결 후 취소 주문의 FILLED 확정 | `CryptoOrderSynchronizer.settle`, `CryptoLedgerRecorder:60` | 업비트 시장가 매수(금액 지정)는 다 체결돼도 자투리 때문에 `cancel`로 끝나는 일이 흔하다. 주식(CANCELLED 유지)과 달라도 의도다(CQ-61, 2026-11-08). |
| `CancelledOrderFillRecorder` 바깥 try/catch | `CancelledOrderFillRecorder.java:36-41` | 도달 불가가 아니다. `tryGet`은 조회 예외만 흡수하고 `ifPresent` 람다의 `OrderFill` 생성(null 금액 NPE 등)은 던질 수 있다. 지우면 한 그룹 실패가 다른 그룹까지 중단시켜 동작이 바뀐다(CQ-56, 2026-11-08). |
| `findPlannedOrPlaced*` 포트 이름(SUBMITTING도 포함) | `OrderPort`·`OrderPersistenceAdapter` | 포트·호출처 전반의 개명인데 주석으로 이미 정정했다. 이름 변경 이득이 비용보다 작다(CQ-56, 2026-11-08). |
| 테스트 FQCN 전수 import 전환 | trading-core test 약 269개 파일 | 기능 이득이 없다. 파일을 건드릴 때만 고친다(CQ-56, 2026-11-08). |
| `CycleCompletedEvent`/`CycleEndedEvent` 필드 동일 | `trading/application/event/` | 발행 흐름(배치 정상 종료 vs 수동 체결 보정)과 리스너 phase가 달라 병합하지 않는다. 한 흐름에서 동시 발행 없음(발행처 `CycleRotationService:54`, `ManualTradeCorrectionService:111`). |
| stats 2곳 중복(기록만) | `PlainDecimalSerializer`(shared contract·stats dto), `Instant.parse(cursor)`(`DashboardController`·`TradingCycleController`), `CryptoMarketIndicators.Premium`/`DailyIndicator` 동형, `EquityPoint`/`BacktestPoint`, `BacktestEngine` initialHoldings null 폴백, `InvestmentIndexCalculator` `laterPosition` 병합 | 2곳(추출 기준 미달). `BacktestPoint`는 프론트 차트 재사용이 주석에 명시돼 있다(stats 감사 2026-11-08). |
| stats 평가액 계산 2종 | `TradingStatsService`(종가 없으면 평단 대체) vs `InvestmentIndexCalculator`(종가 없으면 제외) | 곡선·요약은 연속성이, 투자지수(TWR)는 실평가가 필요해 용도가 다르다(stats 감사 2026-11-08). |
| stats 서비스 `to` null → today 인라인 | `AccountStatisticsService`·`TradingStatsService`·`InvestmentPointsQueryService` 등 | CQ-04에서 null 기본값을 호출부에 남기기로 했다(CQ-17 Clock 주입과 충돌 방지). |
| stats 어댑터 빈 응답 → 빈 결과 | `BinanceMarketDataAdapter` 롱숏, `FrankfurterFxRateAdapter`, `UpbitMarketDataAdapter.getKrwPrices` | 소비처가 모두 `tryGet`이고 다음 단계(`LongShortRatio.of` 등)가 빈 값을 실패로 바꾼다. 미결제약정만 가드가 없어 CQ-43에서 고쳤다. |
| `TradingPreviewService` `TradingAccount.from(account).brokerRef().broker()` 우회 | `trading/application/service/TradingPreviewService.java:79` | 군더더기처럼 보이지만 trading `application.service`는 `Account` 애그리게이트를 직접 쓰지 못한다(`HexagonalArchitectureTest.trading_batch_path_must_not_carry_account_aggregate`, 제외 목록 밖). 투영 경유가 정답이다(CQ-64, 2026-11-09). |
| 엔티티 저장 패턴 두 갈래(`findOrCreate` vs `new`+`setId`) | `StrategyCyclePersistenceAdapter:120`, `CyclePositionPersistenceAdapter:234`, `CycleCapitalEventPersistenceAdapter:38` vs `findOrCreate` 6곳 | 현재 호출처가 모두 id=null 신규 저장이라 merge 덮어쓰기가 일어나지 않는다. update 경로가 생기면 재검토한다(trading 감사 2026-11-08). |

### CQ-39 PRIVACY 전략 원장 변화와 체결 주문 합이 어긋남
- 주제: 원장 정합성 조사. 유형: 불일치(원인 미확정). 위험: 중간.
- 위치·근거: PRIVACY 전략(사이클 시작 2026-10-24)의 원장 변화(+189주, 예수금 −3,440.22)와 체결 주문 11건 합(+103주, −4,672.66)이 86주·$1,232.44 어긋난다.
- 수정 방향: 기록 없는 투자금(주식) 추가 또는 보정 추정 — 운영 읽기 전용 조회(`ssh kista-server` 후 SELECT)로 사이클 포지션·주문·수동 보정 이력을 대조해 확인한다. 투자금 이력(CQ-35)은 배포 이후분만 남으므로 이 건은 소급 확인 불가일 수 있다.
- 1차 조사(2026-11-06, 읽기 전용): 일자별 스냅샷 변화와 그날 FILLED 주문을 대조하면 10-27·10-28·10-29·10-30·11-04는 정확히 일치하고, 어긋남은 두 날짜에만 있다.
  - 10-31 리포트 행: +131주·예수금 −2,394.82 vs 체결 BUY 88주(44@73.69, 44@73.70) −6,485.16 → 차이 **+43주·예수금 +4,090.34**. 평단 변화로 역산한 43주 원가 ≈ $4,329(주당 ≈100.7). 매수로는 예수금이 늘 수 없어 같은 행에 투자금(주식 43주 + 예수금) 추가가 제자리 반영된 것으로 추정.
  - 11-05 리포트 행: +133주·예수금 −8,709.92 vs 체결 BUY 90주@65.02 −5,851.80 → 차이 **+43주·예수금 −2,858.12**(주당 ≈66.5). 그날 재캡으로 BUY_01 90@69.05가 CANCELLED되고 90@68.26가 FILLED — 취소 전 부분 체결 43주가 마감 리포트(브로커 체결)에는 잡혔지만 `orders.filled_quantity`(취소 주문)에는 기록되지 않은 것으로 추정. 결과 예수금이 5.03까지 떨어져 원장 상한을 넘는 이중 매수 가능성.
  - 11-03의 −0.22는 반올림.
- 2차 조사(2026-11-06, 운영 DB 읽기 전용 + 사용자 확인):
  - **재캡 부분 체결 가설 기각.** 11-05 CANCELLED BUY_01 90@69.05는 `external_order_id`가 NULL이다. 19:30Z에 PLANNED로 저장됐고, 20:30:00.15 재캡에서 취소됐다(대체 90@68.26은 20:30:00.149 생성). 브로커에 접수된 적이 없어 부분 체결이 생길 수 없고, 리포트 `ownIds`에도 들어가지 않는다. `CancelledOrderFillRecorder`·재캡 경로 결함은 아니다.
  - **10-31 +43주는 10-29 주문 1878526의 수동 반영이다(사용자 확인).** 이 주문은 BUY_01 43@100.62 지정가다. 05:10 리포트가 CANCELLED로 기록한 뒤 06:10 KST 애프터장에서 체결됐다(f0ae5ee5f·meritz-api.md 실측). 평단을 재현하면 (348×119.30 + 6,485.16 + 43×100.62) / 479 = 109.245이고, HALF_UP 반올림하면 저장값 109.25와 같다. 예수금 차이 +4,090.34에 43×100.62를 더하면 8,417.00으로 떨어진다. 즉 체결 원가(−4,326.66)를 예수금에서 빼지 않고 오히려 현금을 더한 형태다. 수동 반영 경로가 무엇이었는지에 따라 의도된 투자금 추가였는지 판정이 갈린다.
  - **11-05 +43주·예수금 −2,858.12(주당 66.47)도 리포트 밖 수동 변경이다(사용자 확인).** 그날 자기 주문 중 43주 체결이 없다. 11-04 행(8,714.95/473)은 직전 행과 SELL 50@73.24로 정확히 재현되므로 그 이후 변경이다.
  - 근거를 남길 수단이 없었다. 수동 반영은 `cycle_capital_event`(11-06 배포)보다 앞서 이력에 남지 않았다. `orders`에 UNKNOWN 보정 행이 없다(수동 체결 보정 API 미사용). caddy는 접근 로그를 남기지 않고, kista-trading 로그는 11-06 재생성으로 사라졌다.
  - 1878526은 `orders`에 CANCELLED·`filled_quantity` null로 남는다. 그래서 주문 합 대조에서는 이 43주가 영구 차이로 보이고, 원금·실현손익 집계도 이 체결을 보지 못한다.
- 실보유 대조(2026-11-06, 사용자가 메리츠 앱에서 확인): SOXL 606주로 원장 606과 일치한다. 수동 반영 2건은 실재하는 주식을 반영한 것이다.
- 운영 정리(2026-11-06): 10-31 예수금 +4,090.34는 의도한 투자금 추가다(사용자 확인). 1878526 행은 `filled_quantity` 43, `filled_price` 100.62로 채웠다. 상태는 CANCELLED로 유지했다(취소 후 체결 기록 관례와 같다).
- 상태: closed(2026-11-06) — 코드 결함 없음. 원인은 1878526 애프터장 체결 누락(f0ae5ee5f에서 수정)과 이를 메운 수동 원장 변경 2건이다.

### CQ-40 DCA 코인 경로의 금액 자릿수 리터럴 2
- 주제: 금액·수량 스케일. 유형: 비일관성(기존 버그 의심). 위험: 중간(DCA 공식).
- 위치: `trading/application/service/StrategyCreationService.java` DCA 초기 투입 원가 `setScale(2, HALF_UP)`, `matching/domain/strategy/DcaConditions.java` 평단 기준 목표가 `setScale(2, HALF_UP)`(~73)·`money()` 표시(~103).
- 근거: DCA는 Upbit(KRW)·Binance(USDT) 코인 계좌에서도 돈다. KRW 시장에 소수 2자리를 쓰는 셈이다. 같은 모듈의 `DcaCycleOrderStrategy`는 이미 `Currency`/`Market` 기반 자릿수를 쓴다.
- 수정 방향: 계좌 시장의 `amountScale()`로 바꾼다. KRW에서는 값이 바뀌므로 경계 테스트를 먼저 넣는다. 원장 `numeric(20,2)` 저장값과의 관계(원장은 통화 무관 2자리)를 함께 정한다.
- 출처: CQ-20 작업 중 제외 site(2026-11-07).
- 상태: done(1566604d4). 조건 목표가·가격·금액 표시는 계좌 `Market.amountScale()`(KRW 정수), RSI·공포탐욕지수 표시는 2자리 유지, start_amount는 `AccountBalance.LEDGER_SCALE`(값 불변).


### CQ-41 전략 소유권 검증 2줄 반복
- 주제: 소유권 검증. 유형: 중복.
- 위치: `trading/application/service`의 `StrategyService`(6곳)·`DcaConfigService`·`ManualTradingService`·`CapitalInjectionService`·`OrderCancelService`·`DcaExecutionQueryService`·`VrReconfigureService`·`TradingPreviewService`·`StrategyHistoryQueryService`(2곳) 16곳.
- 근거: `findByIdOrThrow` 뒤 `requireOwnedAccount(strategy.accountId(), requesterId)` 2줄이 반복됐고 `StrategyService.requireOwnedStrategy`는 1곳만 썼다. Account를 쓰는 곳 10곳, 버리는 곳 4곳.
- 상태: done(7f4d9be11). `support/OwnedStrategy`(record strategy·account, `load()`) 하나로 통일. `stats` `InvestmentPointsQueryService:49-50` 1건은 모듈 경계상 별도(추출 기준 3곳 미달).

### CQ-42 미사용 import·파라미터 잔재
- 주제: 불필요한 코드. 유형: 죽은 코드.
- 위치: trading-core `trading`·`stats`의 미사용 import 21개(대부분 `Strategy`가 `shared.kernel`로 옮겨진 뒤 남은 import), `TradingPreviewService.buildPreview`의 `account`·`TradingCandidatePlanner.allocateAndSave`의 `firstContext` 파라미터.
- 상태: done(7f4d9be11). 가드는 두지 않는다(유지 목록 "미사용 import 빌드 가드").

### CQ-43 stats 모듈 비일관·불필요 코드 (2026-11-08 감사)
- 주제: 일관성·불필요한 코드. 유형: 비일관·죽은 코드.
- 위치: `AlpacaCandleAdapter`(빈 응답 문구에 symbol 노출·미사용 `Bar.v`), `DashboardController`(size `Math.min`만), `InvestmentIndexCalculator.compareCycles`(startDate 우선 — 포트 조회·곡선은 createdAt), `CryptoMarketIndicators.OpenInterest`(빈 이력 가드 없음), `CoinMarketCapAdapter`(quotes null 미검사·NPE catch), `CryptoMarketIndicatorService` 별칭 변수, `PortfolioService` 필드명, `BacktestEngine` 같은 패키지 import 외.
- 상태: done(1b1eb7cd1). 회귀: `InvestmentIndexCalculatorTest.시작예정일_전_재설정된_사이클도_생성순으로_체인을_잡는다`·`CryptoMarketIndicatorServiceTest.미결제약정_이력이_비면_미결제약정만_null`(변이 확인 완료).

### CQ-44 시작예정일 전 VR 재설정이 startDate 역전 사이클을 만든다 (의심, 정책 판단 필요)
- 주제: 상태 전이 가드. 유형: 누락 가드.
- 위치: `VrReconfigureService.reconfigure`(보관·VR 여부만 검사), `CycleSnapshotCreator.reconfigureVrCycle`(옛 사이클 `markEnded(today)`, 새 사이클 startDate=오늘), `StrategyCreationPersister:69`(첫 사이클 startDate=시작예정일).
- 근거: 미래 시작예정일 VR을 시작 전에 재설정하면 옛 사이클은 endDate < startDate, 새 사이클은 더 이른 startDate로 남는다. 운영 조회(2026-11-08) 역전 쌍 0건·`end_date < start_date` 0건. stats 정렬은 CQ-43에서 생성순으로 맞춰 영향이 없고, `InvestmentIndexCalculator.addEntryFlow`가 시작 안 된 사이클의 스냅샷으로 진입 흐름을 만드는 한계는 남는다.
- 상태: done(86c04774e) — 거부 대신 허용을 택했다. 시작 전(배치와 같은 `StrategyCycle.hasStartedBy(nextTradeDate)` 기준) 재설정은 새 사이클 없이 첫 사이클을 제자리에서 갱신한다(버전 교체·startAmount·포지션 `updateBalance`·사이클 VR 상세, startDate 유지, 새 사이클 알림 없음). 같이 발견한 날짜 불일치도 고쳤다: 시작 후 재설정은 옛 사이클 종료일이 `nextTradeDate`(04:30 이후 내일)라 새 사이클 시작일(오늘)과 하루 겹쳤는데, 이제 둘 다 오늘(KST)이다. 운영 VR 사이클 전환 쌍은 0건(2026-11-08)이라 백필은 없다. 램프 주수 음수는 0으로 보정한다. `addEntryFlow` 한계는 원인인 '대체된 시작 전 사이클'이 더 생기지 않고 기존 데이터도 0건이라 사실상 해소됐다(계산기 코드는 그대로).

## 2026-11-08 tradingcore.trading 깊이 감사 (CQ-45~)

- 감사 시점 HEAD `4cf3e9e8c`. 대상은 `trading-core/.../tradingcore/trading` main 280파일(약 16,000줄)이다. 저비용 서브에이전트 4개가 배치·주문 흐름, 전략 설정·도메인, inbound 어댑터, outbound·영속성으로 나눠 탐색했다. 판정은 메인 세션이 했다. ✔는 메인 세션이 직접 재확인한 위치다.
- 운영 조회(2026-11-08, 읽기 전용): 비보관 전략 8건, 같은 계좌·종목 중복 0건, 열린 사이클이 없는 ACTIVE VR 전략 0건.
- 제외한 항목:
  - 파라미터 8~10개 메서드(`BuyOrderPriceCapper.capIfNeeded` 등): 10-arg는 c12a04aa7(2026-10-25)부터 있었다. 2026-10-31 리뷰의 "긴 파라미터는 동일 타입 인접 2쌍 이상인 7곳만" 판단보다 앞서므로 재제안이다.
  - divisionCount 조회 폴백: 유지 목록 "조회 경로 `.orElse(DEFAULT_DIVISION_COUNT)` 폴백"과 같다.
  - 엔티티 저장 패턴 두 갈래(`findOrCreate` vs `new`+`setId`): 현재 호출처가 모두 id=null 신규 저장이라 영향이 없다. 유지 목록에 올린다.
  - "활성 최신 버전" 정렬 기준 3종(`version_no` vs `created_at`): 활성 버전이 1개라는 불변식이 깨진 사례가 없다(미확인). 재검토 트리거는 불변식 위반 발견이다.

### CQ-45 VR 롤오버가 사이클 종료와 새 사이클 생성을 두 트랜잭션으로 나눈다
- 주제: 상태 전이. 유형: 버그 의심. 위험: 중간(VR 경로, 공식 수치는 불변).
- 위치: `VrCycleRolloverService.java:147`(`markEnded`) ✔, `:157`(`cycleSnapshotCreator.createVrCycleAndSnapshot`) ✔. 상위 `CyclePositionPersistor.java:87`·`TradingExecutionFacade`에 `@Transactional`이 없다 ✔.
- 근거: 새 사이클 생성이 실패하면 ACTIVE VR 전략에 종료된 사이클만 남는다. `StrategyService.reopenCycleIfEnded`(`:175-178`) ✔는 "VR은 rollover/재설정이 원자적으로 대체 사이클을 만든다"는 전제로 VR을 제외해서 재개로도 풀리지 않는다. 그런데 그 전제가 롤오버에서는 성립하지 않는다. 같은 작업인 `CycleSnapshotCreator.reconfigureVrCycle`(`:76`)은 한 트랜잭션이다. CQ-29(INFINITE·PRIVACY의 `markEnded`)와 같은 유형이다.
- 수정안: `CycleSnapshotCreator`에 `markEnded`까지 포함한 `@Transactional` 롤오버 메서드를 두고 `rollIfDue`가 그것을 호출한다.
- 가드: DB 테스트로 "새 사이클 생성 실패 시 `markEnded`도 롤백"을 잠근다(`CyclePositionPersistorDbTest` CQ-29 패턴).
- 상태: done(9554a1607).

### CQ-46 같은 계좌·종목 중복 등록이 check-then-act라 경합에 뚫린다
- 주제: 상태 가드. 유형: 누락 가드. 위험: 중간(마이그레이션).
- 위치: `StrategyCreationService.java:96,193-197` ✔(`validateUniqueTicker`, `register`는 `NOT_SUPPORTED`), `StrategyCreationPersister.java:44` ✔. `trading.strategy`에는 `idx_strategy_account_id`만 있고 유니크 제약이 없다(`V1__init.sql:523`) ✔.
- 근거: 더블클릭이나 재시도로 두 요청이 동시에 검증을 통과하면 같은 종목 전략이 두 개 생긴다. 운영 중복은 0건이다.
- 수정안: trading 마이그레이션에 `UNIQUE (account_id, ticker) WHERE deleted_at IS NULL AND status <> 'ARCHIVED'` 부분 인덱스를 추가하고, `DataIntegrityViolationException`을 `DuplicateStrategyTickerException`(409)으로 변환한다.
- 가드: 인덱스 자체와 저장 경합 IT.
- 상태: done(e2fe66ed8, 테스트 픽스처 028a1cdd0). 배포 전 운영 중복 0건 재확인 필요(2026-11-08 0건).

### CQ-47 개장 후 바로주문의 AT_OPEN 접수 시세 조회가 raw 호출이다
- 주제: 브로커 실패 처리. 유형: 비일관·조용한 실패. 위험: 낮음.
- 위치: `ManualTradingService.java:145-148`(`fetchStartPriceOrNull`, `tryGet`) ✔ vs `:160` raw `priceFetcher.fetchPrices(...).get(...)` ✔.
- 근거: `:160`은 PLANNED 저장 뒤에 실행된다. 시세 조회가 실패하면 사용자는 503을 받는데 주문은 PLANNED로 남는다. 같은 서비스의 다른 조회는 실패 시 캡 미적용(null)으로 진행한다.
- 수정안: `fetchStartPriceOrNull`을 재사용한다(null이면 캡 없이 원본 접수 — 기존 계약).
- 가드: 시세 조회 실패 시 접수가 진행되는지 확인하는 단위 테스트.
- 상태: done(a0877d62e).

### CQ-48 오래된 스냅샷 `strategyPort.save`가 상태를 덮어쓴다
- 주제: 상태 전이. 유형: 비일관. 위험: 낮음~중간.
- 위치: `support/CycleCloser.java:23`(`save(strategy.withStatus(PAUSED))`) ✔, `StrategyService.java:253`(`update`의 `save`) ✔. 조건부 갱신은 `StrategyPort.java:38,56`(`updateStatusUnlessArchived`·`pause`) ✔에 있다.
- 근거: `StrategyPort`가 "오래된 스냅샷 save가 보관을 덮어쓰는 경합" 때문에 조건부 갱신을 만들었는데 두 곳이 남았다. 동시에 보관하면 ARCHIVED가 PAUSED나 ACTIVE로 되돌아갈 수 있다(재현 미확인).
- 수정안: `CycleCloser`는 `strategyPort.pause(id)`를 호출하고 `withStatus(PAUSED)`를 반환한다. `update`는 `cycleSeedType`만 쓰는 조건부 메서드를 추가한다.
- 가드: 어댑터 테스트로 ARCHIVED 행이 되돌아가지 않음을 잠근다.
- 상태: done(a0877d62e).

### CQ-49 계좌 삭제 cascade 실패의 재시도 경로가 재기동뿐이다
- 주제: 이벤트. 유형: 비일관. 위험: 낮음.
- 위치: `adapter/in/event/AccountCascadeListener.java:31` ✔, `adapter/in/schedule/UserEventResubmitScheduler.java:46-48` ✔(필터는 `UserDeletedEvent`·`UserNotifyProfileChangedEvent`만 통과).
- 근거: 사용자 탈퇴 cascade는 첫 실패 시 알림과 10분 재발행이 있다. 계좌 삭제 cascade는 kista-trading 재기동 재발행에만 기대므로 그 사이 삭제 계좌의 전략·사이클이 남는다. 리스너가 멱등인지는 미확인이다.
- 수정안: 필터에 `AccountDeletedEvent`를 추가하고 메서드·주석 이름을 "cascade·사용자 동기화 이벤트"로 넓힌다. 리스너 멱등은 먼저 확인한다.
- 가드: `UserEventResubmitSchedulerTest`에 `AccountDeletedEvent` 케이스를 추가한다.
- 상태: done(517989e81).

### CQ-50 "증권사에 접수된 전략 주문" 술어 4곳 반복
- 주제: 중복. 유형: 중복. 위험: 낮음.
- 위치: `StrategyService.java:148` ✔, `TradingService.java:199-200` ✔, `StrategyLedgerBudget.java:59` ✔, `DcaPlanInputCollector.java:185-186` ✔.
- 근거: `externalOrderId != null && !UNKNOWN_LEG.equals(orderLeg)`가 4곳에 있고, 수동 보정 행 판정이 바뀌면 함께 바뀐다(CLAUDE.md 추출 기준 충족).
- 수정안: `Order.isBrokerSubmitted()`(이름은 착수 시 정함)를 추가하고 4곳을 교체한다.
- 가드: 없음(도메인 메서드 단위 테스트만).
- 상태: done(b0147f672).

### CQ-51 DCA 주문 leg 접두사 `"DCA_"` 4곳
- 주제: 중복. 유형: 비일관. 위험: 낮음.
- 위치: 생성 `matching/domain/model/DcaRule.java:90` ✔, 역변환·판별 `CryptoOrderSynchronizer.java:110` ✔(리터럴), `TradingCandidatePlanner.java:278,285,288` ✔·`TradingOrderExecutor.java:151,159,161` ✔(각자 private 상수).
- 수정안: `DcaRule`에 `isDcaLeg(leg)`·`ruleKeyOf(leg)` 정적 메서드를 두고 3곳을 교체한다.
- 가드: 없음.
- 상태: done(b0147f672).

### CQ-52 배치 락 이름·보유시간 리터럴 7곳
- 주제: 중복. 유형: SSOT 우회. 위험: 낮음.
- 위치: `TradingOpenScheduler.java:34,39,47` ✔, `TradingCloseScheduler.java:54,71,79,84` ✔. SSOT는 `TradingBatchJob.java:5-6` ✔(`lockName()`, 주석상 체크포인트 키와 같은 값).
- 근거: 락 이름과 체크포인트 키가 다르면 재개 판정이 어긋난다.
- 수정안: `TradingBatchJob.OPEN.lockName()`과 클래스 상수 `LOCK_TTL`로 바꾼다.
- 가드: 없음.
- 상태: done(b0147f672).

### CQ-53 포지션 포트·쿼리의 죽은 메서드와 커서 쿼리 soft-delete 필터 누락
- 주제: 불필요한 코드·영속성 일관성. 유형: 죽은 코드·비일관. 위험: 낮음.
- 위치:
  - 죽은 코드: `CyclePositionPort.java:49`(3-인자 `findByAccountId`)·`:52`(`findByStrategyIdAndDateRange`), 어댑터 `CyclePositionPersistenceAdapter.java:99-109`, `CyclePositionJpaRepository.java:75`(`findByAccountIdAndDateRange`)·`:81`(`findAllByOrderByCreatedAtDesc(Pageable)`). main·루트 test에서 호출처 0건이다(어댑터 테스트 제외) ✔. JPA `findByStrategyIdAndDateRange`는 어댑터 `:132`가 쓰므로 유지한다.
  - 필터 누락: `CyclePositionJpaRepository.java` `findByAccountIdWithCursor`에 `a.deleted_at IS NULL`이 없다 ✔. 같은 JOIN을 쓰는 `findRecentByUserId`·`findByUserIdAndRange`에는 있다 ✔. 호출 전 계좌 소유 검증이 삭제 계좌를 막으므로 실제 영향은 없다.
- 수정안: 죽은 메서드 4개와 해당 어댑터 테스트를 지우고 필터를 추가한다.
- 가드: 없음.
- 상태: done(b53504f24).

### CQ-54 `nextVersionNo`가 soft delete 순서에 의존한다
- 주제: 영속성. 유형: 잠재 함정. 위험: 낮음.
- 위치: `StrategyVersionJpaRepository.java:32`(JPQL MAX — `@SQLRestriction`이 삭제 버전을 뺀다), 호출부 주석 `DcaConfigService.java:46` ✔·`CycleSnapshotCreator.java:114-118` ✔.
- 근거: 호출 순서가 바뀌면 삭제된 버전 번호를 재사용해 `uq_strategy_version_strategy_version_no`(V1:544)와 충돌한다. 현재 순서는 안전하다.
- 수정안: 삭제 행을 포함하는 native `MAX(version_no)`로 바꾸고 호출부의 순서 주석을 지운다.
- 가드: 어댑터 테스트 "삭제된 버전이 있어도 다음 번호는 최대+1".
- 상태: done(b53504f24).

### CQ-55 거래일 후보 쌍·마감 리포트 창 판정의 중복
- 주제: 중복. 유형: 중복. 위험: 낮음.
- 위치:
  - `Stream.of(today, nextTradeDate).distinct()`: `StrategyService.java:146`, `CapitalInjectionService.java:148`, `OrderCancelService.java:83`(3곳, 서브에이전트 보고).
  - 리포트 창 거절: `CapitalInjectionService.java:230-239`·`ManualTradeCorrectionService.java:127-136`(본문 동일, 주석이 서로 참조). `TradingService.java:229-232`는 모양이 다르다.
- 수정안: 후보 쌍은 `DstInfo`(또는 거래일 헬퍼)에 하나로 둔다. 리포트 창은 `CloseReportScope`에 위임 메서드를 둔다.
- 가드: 없음.
- 상태: done(d9f9c3743).

### CQ-56 trading 잔손질 묶음 (동작 불변)
- 주제: 일관성·불필요한 코드. 유형: 보일러플레이트·죽은 코드·stale 주석.
- 위치(서브에이전트 보고, 착수 시 재확인):
  - `OrderPersistenceAdapter`: `OPEN_STATUSES` 상수(`:33-34`)를 `:166`이 쓰지 않음. `findPlannedOrPlaced*`가 SUBMITTING도 포함하는데 이름·`OrderJpaRepository` 주석은 PLANNED/PLACED. `toDomainList`(`:279`)가 있는데 `:160,166,171` 인라인. 다른 어댑터와 달리 이유 없이 public(`:30`).
  - `StrategyCyclePersistenceAdapter.java:52-77`: load-set-save 3벌과 stale 주석 "markFilled와 동일".
  - `CancelledOrderFillRecorder.java:36-41`: `tryGet` 바깥 try/catch가 도달 불가.
  - `OrderSubmissionReconciler.java:44-49`: 수기 생성자(`@RequiredArgsConstructor`로 대체 가능).
  - `TradingCandidatePlanner.java:136,213`: `PriceSnapshot` 현재가 null 분기(옆 줄은 `prevCloseOrNull` 사용).
  - `support/TradingBalanceLoader.java:32-33`: `loadBalanceOrThrow`가 항상 `skipReason=null`인 래퍼를 반환하고 주석은 ISE(실제는 NSEE).
  - `Order` 금액 `price × quantity` 5곳(`StrategyLedgerBudget:50,67`, `CryptoDcaRoundExecutor:218`, `TradingPreviewService:216,273`), `new AccountBalance(ZERO, null, ZERO)` 2곳.
  - `TradingBatchRunPersistenceAdapter.java:33,36`: SQL `now()`(다른 어댑터는 `Instant.now(clock)`).
  - `TradingCycleController.java:264-269`: `getStrategyOrders`만 컨트롤러에서 기간 기본값을 정한다(같은 컨트롤러의 다른 조회는 서비스가 정함). DCA 회차 이력은 서비스(`DcaExecutionQueryService:42-43`, 상한 100)가, history는 컨트롤러(상한 200)가 size를 자른다.
  - `TradingSchedulerInternalController` `/open`·`/close`에 `@Operation` 없음(같은 패키지 내부 컨트롤러는 모두 있음).
  - stale 주석: `TradingExecutionUseCase.executeManually`("INFINITE 전용", 실제는 DCA만 거부), `StrategyCreationRequest`(변환 주체).
  - 문서: 스트림 컨슈머 복구 `@Scheduled`가 `adapter/in/redis`에 있다(`UserEventStreamConsumer:63`, root 2곳 동형, `RedisStreamSubscriber` 설계). `constraints.md`에 예외를 한 줄 추가한다.
- 가드: 없음.
- 상태: done(d6e89b7fa). 미처리: `CancelledOrderFillRecorder` 바깥 try/catch(람다 안 `OrderFill` 생성이 던질 수 있어 도달 불가 아님, 지우면 동작 변경)·`getStrategyOrders` 기간 기본값 서비스 이동·size 상한 100/200 통일·`findPlannedOrPlaced*` 포트 이름(주석만 정정).

### CQ-57 정책 설정 JSON 섹션 누락 시 NPE
- 주제: 누락 가드. 위험: 낮음.
- 위치: `adapter/out/persistence/policy/TradingPolicySettingsPersistenceAdapter.java:62-68`(`(ObjectNode) root.get(section)`), catch `:53`은 Jackson·IAE만 잡는다. 예외 문구가 영어다.
- 수정안: 섹션이 없으면 기본 섹션을 채우고 문구를 한국어로 바꾼다.
- 가드: 섹션 없는 JSON 단위 테스트.
- 상태: done(b53504f24).

### CQ-58 등록 예수금 부호·자릿수 검증이 DCA에만 있다
- 주제: 입력 검증. 유형: 비일관·누락 가드. 위험: 낮음~중간.
- 위치: `StrategyCreationService.java:101-102` ✔(DCA만 `dcaDeposit` 검증 `:182-192` ✔, 나머지 타입은 null→0만), `TradingCycleRequest.java:21-22`(검증 어노테이션 없음), `CapitalInjectionService.requireAmountScale`(`:195`, 같은 검사 다른 문구), `StrategyService.updateSeed`(`signum<=0`만).
- 근거: INFINITE·PRIVACY·VR 등록에 음수나 소수 3자리 예수금이 들어갈 수 있다. INFINITE는 `validateInfiniteMinSeed`가 fail-open(유지 목록)이라 시세 조회 실패 시 통과한다.
- 수정안: `requireAmountScale`을 공용 검증 하나로 올려 등록 전 타입·시드 수정·투자금 추가가 같이 쓴다.
- 가드: 타입별 음수·자릿수 등록 거부 테스트.
- 상태: done(8ece9ebdf). DCA 자릿수 문구가 "예수금은…"에서 "금액은…"으로 통일됐다.

### CQ-59 `markCancelled`·`markFilled`가 잠금·상태 검사 없이 덮어쓴다 (정책 판단 필요)
- 주제: 상태 전이. 위험: 중간.
- 위치: `OrderPersistenceAdapter.java:253-256,269-276` ✔(`mutate`), 대조 `markFailed` `:258-265` ✔(`lockOrConflict`+`requireStatus`).
- 근거: 접수 계열은 행 잠금과 상태 검사를 하는데 취소·체결 기록은 last-write-wins다. 증권사 상태를 진실로 보는 의도일 수 있다. 실제 경합 경로는 미확인이다.
- 결정(사용자, 2026-11-08): 증권사 상태 우선 설계는 유지하고, `markCancelled`가 이미 FILLED·PARTIALLY_FILLED인 행을 덮지 못하게만 막는다. 취소 후 체결 기록은 기존 `markFilled(…, CANCELLED)` 경로를 유지한다. 의도는 주석으로 고정한다.
- 가드: 어댑터 테스트 "FILLED 행에 markCancelled → 상태 불변".
- 상태: done(a0877d62e). 행 잠금은 넣지 않았다(결정 범위 밖, 조회→검사→저장 사이 경합 창 잔존).

### CQ-60 수동 마감 트리거가 창 밖이면 202 뒤에 조용히 거부된다 (정책 판단 필요)
- 위치: `TradingCloseScheduler.java:63-70` ✔, `TradingSchedulerInternalController.java:44-53` ✔(무조건 202 + 가상 스레드), root `TradingSchedulerCommandHttpAdapter.java:28-30`·`AdminTradingSchedulerController.java:28-30`.
- 근거: 관리자 알림은 가지만 호출 응답으로는 거부를 알 수 없다. 개장 `runNow`에는 창 판정이 없다.
- 결정(사용자, 2026-11-08): 현행 유지(202 + 관리자 알림). 비대칭 이유를 `TradingSchedulerInternalController`·`TradingCloseScheduler.runNow`에 주석으로 남긴다. 응답 코드 불변이라 kista-ui 영향 없음.
- 상태: done(주석, 이 세션 마지막 커밋). 현행 유지(202 + 관리자 알림), 비대칭 이유를 `TradingSchedulerInternalController`·`TradingCloseScheduler.runNow`에 주석으로 남김.

### CQ-61 코인 부분체결 후 취소 주문이 FILLED가 된다 (정책 판단 필요)
- 위치: `CryptoLedgerRecorder.java:60`(`markFilled(…, FILLED)`), `CryptoOrderSynchronizer.java:115,130`. 주식은 CANCELLED를 유지하고 체결량만 기록한다(`CancelledOrderFillRecorder`, `OrderCancelStateWriter`).
- 결정(사용자, 2026-11-08): 의도로 유지한다. 업비트 시장가 매수(금액 지정)는 다 체결돼도 자투리 때문에 `cancel`로 끝나는 일이 흔하다. 이유를 `CryptoOrderSynchronizer.settle` 주석과 유지 목록에 남긴다.
- 상태: done(주석, 이 세션 마지막 커밋). 의도 유지, 이유를 `CryptoOrderSynchronizer.settle` 주석과 유지 목록에 남김.

### CQ-62 미리보기 계좌 현금·보유 경고가 정지 전략 주문까지 합산한다 (정책 판단 필요)
- 위치: `TradingPreviewService.java:106`(`!isArchived()`만 거름), `:202-` `attachAccountCashCheck`·`attachAccountHoldingsCheck`.
- 결정(사용자, 2026-11-08): 경고 합산만 ACTIVE 전략으로 좁히고 카드 표시는 유지한다. 응답 형식 불변.
- 가드: 정지 전략 주문이 경고 합산에서 빠지는 단위 테스트.
- 상태: done(517989e81).

### CQ-63 시드 수정·인출 뒤 INFINITE 최소 시드를 다시 확인하지 않는다 (정책 판단 필요)
- 위치: `StrategyService.updateSeed`, `CapitalInjectionService.java:169`(`signum() > 0`만). `minRequiredDeposit` 호출은 등록(`StrategyCreationService:160`)과 `CycleRotationService:92`뿐이다.
- 결정(사용자, 2026-11-08): `updateSeed`·인출에도 등록과 같은 fail-open 최소 시드 검사를 넣는다(시세 조회 실패 시 건너뜀, 미달이면 400). 검사는 등록과 한 곳을 공유한다.
- 가드: 시드 수정·인출 미달 거부 테스트.
- 상태: done(8ece9ebdf). 전일종가 조회는 `NOT_SUPPORTED` 진입점(`StrategyService.update`·`CapitalWithdrawalService`), 쓰기는 `StrategyUpdateWriter`·`CapitalInjectionService` 트랜잭션.

## 2026-11-09 tradingcore.trading application/service 코드철학 감사 (CQ-64~)

- 감사 시점 HEAD `ce902ce16`. 대상은 `trading/application/service` 69파일(약 7,700줄)이다. `/api-audit --focus 코드철학`으로 저비용 서브에이전트 3개가 탐색했고 판정은 메인 세션이 했다. ✔는 메인 세션이 직접 재확인한 위치다. 경로 접두사 `trading/application/service/`는 생략한다.
- 제외한 항목: `DcaExecutionRecorder.recordSkipped`(8인자)·`TradingPreviewService.buildPreview`(10인자)는 CQ-45~ 감사의 "8~10인자 메서드 재제안" 판정과 같다. 평단 가중평균 3곳(`AccountBalance`·`CapitalInjectionService:103`·`VrReconfigureService:190`)은 체결 vs 투입, VR은 0주일 때 null이라 의미가 다르고 공식에 닿는다. MAINTAIN 시드 규칙(`StrategyService:187`·`CycleRotationService:67`)은 2곳이다.

### CQ-64 service 동작 불변 정리 묶음
- 주제: 코드철학. 유형: 죽은 코드·보일러플레이트·stale 주석·가시성. 위험: 낮음.
- 위치:
  - 미사용 지역변수 `Strategy strategy = owned.strategy()`: `StrategyService:73` ✔, `support/StrategyHistoryQueryService:100,114` ✔.
  - 헬퍼 우회: `TradingPreviewService:297` `status() == ACTIVE`(→ `isActive()`) ✔, `:79` `TradingAccount.from(account).brokerRef().broker()`(→ `account.broker()`) ✔, `CapitalInjectionService:169` 수기 `new AccountBalance`(→ `withUsdDeposit`) ✔.
  - 도달 불가 가드: `VrStrategyDetailService:69` `vrDetail == null || cycleVr == null`(호출처 2곳 모두 `Optional.map` 람다 안) ✔, openingPool 가드 `:50-53`·`:70-72` 중복 ✔.
  - `CyclePositionPersistor:41,57,60` `cycleOrderStrategies.of(...)` 4회 호출·`:52` 별칭 변수·`:50-51` 주석 위치 ✔.
  - stale 주석 "application/service/trading 패키지 전용": `StrategyOrderPlanBuilder:30`, `TradingOrderSlots:16`(사용처도 틀림), `VrReconfigureService:44` ✔.
  - 겹치는 개별 import: `TradingService:2-4` ✔, `TradingReporter`.
  - 단순 위임 private 메서드: `TradingService:426` `placeAtOpenPlannedOrders` ✔.
  - `MarketEventNotifier:38` private `notify(type, …)`의 상수 인자·`Object.notify` 이름 충돌 ✔.
  - `OrderSubmitter` 로그 인자 4개 10회·알림 `.formatted` 6회 반복 ✔.
  - `OrderCancelService:93` 로그 본문 `cycleId=`(범위에 strategyId 있음) ✔.
  - `ManualTradeCorrectionService:157-162` FQN `java.util.TreeSet`·`forEach(add)` ✔.
  - 불필요한 public: `CryptoDcaRunner:38` ✔(외부 참조 0), `support/TradingBatchShutdownCoordinator:16`, `VrStrategyDetailService` 메서드.
- 가드: 없음.
- 상태: done(aa8881f3f). OrderSubmitter 알림 문구 헬퍼는 접두사가 서로 달라 하지 않았다(로그 접두사만 `logTag`). `TradingPreviewService:79`의 `account.broker()` 직접 호출은 root ArchUnit 가드 `trading_batch_path_must_not_carry_account_aggregate` 위반이라 b3e82a5f0에서 `TradingAccount` 투영 경유로 되돌렸다(유지 목록).

### CQ-65 서비스의 도메인 술어 직접 판정
- 주제: 코드철학(TDA). 유형: 중복·비일관. 위험: 낮음.
- 위치:
  - 사이클 종료 `endDate() != null`/`== null`: `CapitalInjectionService:203,211`, `CryptoDcaRunner:113`, `ManualTradeCorrectionService:74`, `TradingPreviewService:142`, `StrategyService:180`, `StrategyDetailAssembler:181`, `adapter/in/schedule/BatchContextFactory:57` ✔. stats `InvestmentIndexCalculator:155,250,262`·`CryptoPortfolioService:98`·`TradingStatsService:84`도 같은 판정이다(전역 후보).
  - 수동 보정 행 제외 `!UNKNOWN_LEG.equals(orderLeg)`: `domain/model/Order.java:55`(`isBrokerSubmitted` 안), `CloseReportScope:37`, `ManualTradeCorrectionService:167` ✔.
  - BUY 합계: `TradingPreviewService:298-301`이 `AccountBalance.buyTotal`(matching `:52`)과 본문이 같다 ✔.
- 수정 방향: `StrategyCycle.isEnded()`, `Order.isStrategyOrder()`를 추가하고 호출처를 바꾼다. 미리보기는 `buyTotal`을 재사용한다.
- 가드: 도메인 메서드 단위 테스트.
- 상태: done(be153b5b4). stats 5곳도 함께 바꿨다. `Order`를 mock하는 테스트(`CloseReportScopeTest`·`ManualTradeCorrectionServiceTest`)는 `isStrategyOrder()`도 stub한다.

### CQ-66 서비스 시그니처·명명 정리
- 주제: 코드철학. 유형: TDA·인접 동일 타입·명명. 위험: 낮음~중간(VR 재설정 경로).
- 위치(서브에이전트 보고, 착수 시 재확인):
  - Command null→0 정규화·음수 거부 반복: `CapitalInjectionService:74-85`, `VrReconfigureService:152-171,201-202`.
  - `InsufficientBalanceEvent` 10인자 위치 생성 4곳: `CycleRotationService:78,126`, `TradingCandidatePlanner:262`, `TradingOrderExecutor:170`.
  - `strategy.requiresPrivacyBase() ? privacyBase : null` 3곳: `TradingCandidatePlanner:176,226`, `TradingService:211`.
  - `support/TradingParallelRunner:29` `Object groupKey`(호출처 모두 계좌 UUID).
  - `CapitalInjectionService`가 인출도 담는다. `CapitalWithdrawalService:24` 필드명 `ledgerWriter`.
- 수정 방향: Command 접근자·`validate()`, 이벤트 정적 팩토리, `CycleState` compact 생성자 정규화, `UUID groupKey`, `CapitalLedgerService` 개명(테스트·문서·`flows.yml` 참조 포함).
- 가드: VR 재설정·투자금 추가 기존 테스트로 동작을 고정한 뒤 손댄다.
- 상태: done(1fb18a00c). `ReconfigureVrCommand.validateCapitalAdjustment`는 기존 검사 순서·문구를 그대로 옮겼다.

## 2026-11-09 stats 실거래 성과 로직 감사 (CQ-67~)

- 감사 시점 HEAD `d6a90eedb`. 대상은 summary·equity-curve·cycles·투자지수(`InvestmentIndexCalculator`)·투자 포인트(`InvestmentPointsQueryService`)와 이들이 읽는 원장이다(백테스트·시장지표 adapter 제외). 판정은 메인 세션이 했고, 운영 DB는 읽기 전용 SELECT로 대조했다. 경로 접두사 `stats/application/service/`는 생략한다.
- 운영 전제(2026-11-09): 종료 사이클 0건, `cycle_capital_event` 3건(BINANCE DCA 2건·MOCK DCA 1건). 승률·평균 수익률·종료 사이클 실현손익은 운영 근거가 없고 `TradingStatsServiceTest`로만 확인했다.
- 정책 결정(사용자, 2026-11-09): PRIVACY 통계 원금은 개장 시가 평가 저장, 실현 기준은 사이클 개장 시가, 진행 중 사이클 매도 확정분은 실현에 포함, 시작 후 시드 수정은 이력으로 기록, 끝난 전략 손익은 곡선에 계속 반영, 평균 수익률은 유지 + 문서화, BINANCE 700은 이력 행 백필.
- 작업 순서: CQ-67 → CQ-69 → CQ-68 → CQ-70. CQ-68은 CQ-67의 실현 기준에 의존한다.

### CQ-67 사이클 개장 시가 평가를 저장하고 통계 원금·실현 기준으로 쓴다
- 주제: 통계 정의. 유형: 정의 충돌·잘못된 원금. 위험: 중간(마이그레이션, 저장 경로 여러 곳).
- 위치:
  - PRIVACY 중간 시작의 `startAmount`는 FIDA 배수 환산 명목값이다(`trading/application/service/StrategyStartAmountCalculator.java:48` ✔). 그런데 stats가 이 값을 원금으로 쓴다: `TradingStatsService.java:87-89`(`principal`), `:259`(미실현), `:164`(운용 원금), `:404-408`(사이클 수익률) ✔. 투자지수는 첫 시가 평가를 진입값으로 써서(`InvestmentIndexCalculator.java:266-272` ✔) 같은 전략인데 화면마다 손익 기준이 다르다.
  - 실현손익의 원가가 평단이라 등록 전·이전 사이클 이익이 실현에 섞인다(`CapitalLedgerTimeline.java:64-78` ✔). 보유 0 지름길(`TradingStatsService.java:314` ✔)은 첫 장부가가 `startAmount`와 같다고 가정한다. VR 롤오버 이월 사이클(시작 금액 = 시가, 평단 = 원가)에서는 이 가정이 깨져, 전량 매도 후 종료하면 이월분 이익이 영구히 "미실현"에 남는다. 이 경로는 테스트가 없다(`TradingStatsServiceTest:923-990`은 첫 장부가 = 시작 금액인 경우만 다룬다).
- 근거(운영 SELECT, 2026-11-09): MERITZ PRIVACY는 `start_amount` 66,156.02, 개장 시가 평가 38,289.77, 11-07 자산 27,869.33이다. summary 미실현이 −38,286.69(−57.9%)로 표시되는데 시가 기준으로는 −10,420(−27.2%)이다. 이 전략엔 CQ-39 원장 불일치도 섞여 있다. MERITZ VR은 평단 50.20, 개장 종가 62.01이라 첫 롤오버부터 실현 배분이 흔들린다.
- 수정 방향: 사이클 개장 시가 평가금과 개장 평단(시가)을 저장한다. 저장 경로는 등록, 롤오버, VR 재설정 시작 전·후, 회전, 재개다. 기존 행은 첫 스냅샷 종가로 백필한다. 매매 계약인 `startAmount`는 그대로 둔다. stats 원금은 개장 평가에 추가·인출을 더한 값으로, 실현은 개장 시가 기준으로 바꾼다. 투자지수 진입값도 같은 값과 대조한다. kista-ui 원금 라벨도 확인한다.
- 가드: PRIVACY 중간 시작 원금, VR 이월 사이클 전량 매도 종료, 평단 ≠ 시가 등록 사이클의 단위 테스트. 합계(실현 + 미실현 = 총손익)를 보존하는지 단언한다.
- 처리: `strategy_cycle.open_amount`(trading V17)만 저장했다. 개장 시가는 따로 두지 않는다 — 실현에 필요한 승계 프리미엄 D = 개장 평가 − 개장 장부가가 첫 행에서 나온다(`CapitalLedgerTimeline.openBasisRealized`, 같은 날 매수·매도 동시 체결은 순증감 근사 — `ponytail:` 주석). 저장 경로는 등록·회전/재개(`createCycleAndSnapshot`)·VR 롤오버/시작 후 재설정(`createVrCycleAndSnapshot`)·VR 시작 전 재설정·시드 수정(`resetStartAndOpenAmount`)이다. 백필은 첫 스냅샷 종가 평가(종가 없거나 보유 0이면 `start_amount − 이력 합계` — DCA는 V14 원가 기준 보존), 운영 미리보기(2026-11-09 SELECT)로 바뀌는 행은 MERITZ PRIVACY 1행(66,156.02 → 38,289.77, 미실현 −10,420.44 / −27.2%)뿐임을 확인했다. 투자지수 진입값은 첫 행 종가가 있는 사이클에서 저장값과 같고, DCA(종가 없음)·첫 행에 이력이 걸린 사이클은 다를 수 있다(CQ-69 범위).
- 상태: done(45e2453fb).

### CQ-68 진행 중 사이클의 매도 확정분을 실현손익에 포함한다
- 주제: 통계 정의. 유형: 정의와 UI 문구 불일치. 위험: 낮음.
- 위치: `TradingStatsService.java:270-276`(진행 중 사이클은 실현 0, 전부 미실현) ✔. kista-ui `widgets/stats-overview/StatsKpiRow.tsx:19`의 보조문구는 "매도 확정분"이다.
- 근거: INFINITE 쿼터매도, PRIVACY 매도, DCA SELL처럼 진행 중에 판 금액이 미실현으로 집계된다. 사용자 결정은 실현에 포함하는 것이다(2026-11-09).
- 수정 방향: CQ-67의 개장 시가 기준 장부가 실현(개장 대비 최신 스냅샷)을 진행 중 사이클에도 적용한다. 미실현은 총손익 − 실현이다. kista-ui 보조문구에서 "VR 승계분은 미실현"을 재검토한다.
- 가드: 진행 중 사이클의 부분 매도 실현과 합계 보존 단위 테스트.
- 상태: open(CQ-67 선행).

### CQ-69 투자금 흐름을 이력 하나로 기록하고 투자지수가 읽는다
- 주제: 통계 정확도. 유형: 누락(CQ-35 범위 밖). 위험: 중간.
- 위치:
  - 투자지수가 `cycle_capital_event`를 읽지 않는다. `InvestmentPointsQueryService.java:70-75`가 원본 포지션을 넘기고, `InvestmentIndexCalculator.java:29,112-143`의 외부흐름은 진입·종료·교체뿐이다 ✔. 추가·인출은 최신 포지션 행을 제자리 수정하므로(`trading/.../CapitalLedgerService`) 그날 수익률로 잡힌다. `InvestmentIndexCalculatorTest`에 추가·인출 케이스가 없다.
  - 이력 없이 원장을 제자리 수정하는 경로 2곳이 있다. 시드 수정(`trading/.../StrategyUpdateWriter.java:101-102` ✔ — 보유 0이면 사이클 시작 후에도 허용, `updateCycleStartSnapshot`은 최신 행만 수정)과 VR 시작 전 재설정(`trading/.../CycleSnapshotCreator.java:108-113` ✔)이다. 과거 원금선과 투자지수가 함께 왜곡된다.
- 근거: 운영 BINANCE DCA 사이클 `01a18aaf-…1750`(11-07 +100·−350). 11-06 평가 ≈1,288.55, 11-07 평가 ≈1,038.49라 일 수익률이 −19.4%로 나온다(흐름을 반영하면 ≈0%). 지금은 사용자에게 보이지 않는다(크립토는 PORTFOLIO에서 빠지고, kista-ui `useBenchmarkStrategyOptions.ts:27`도 크립토를 뺀다). USD 전략에서 첫 추가·인출이 생기면 바로 보인다. 시드 수정 왜곡은 운영 이력이 남지 않아 확인할 수 없다(미확인).
- 수정 방향: 순서대로 한다. ① 시드 수정과 VR 시작 전 재설정도 `cycle_capital_event`에 차액을 기록한다(Kind 추가 검토, `existsByCycleId` 거절 규칙과의 관계 정리). ② 투자 포인트 조회가 이력을 읽어 `CapitalLedgerTimeline.restore`로 포지션을 복원하고, 원금 증감을 외부흐름으로 계산기에 넘긴다. ③ BINANCE DCA 11-06의 이력 없는 예수금 700(행 `01a1ae30-e606-…d900`, 투자지수 약 +134% 점프)을 INJECT 1행으로 백필한다. 운영 쓰기라 실행 전에 사용자 승인을 받고, 원인(배포 전 수동 추가로 추정, 미확인)도 함께 확인한다.
- 가드: 계산기 단위 테스트(추가·인출일 수익률 0), 시드 수정 이력 기록 테스트.
- 처리:
  - ① 원금 불변식을 "`open_amount`는 개장 때 1회만 쓰고 이후 원장 변화는 모두 이력"으로 고정했다. 시드 수정과 VR 시작 전 재설정은 `startAmount`만 바꾸고 차액을 새 Kind `RESET`으로 남긴다. 시드 수정은 최신 행 예수금 차액을 쓰고, VR은 원가 기준 장부가 차액을 쓴다. 이력을 남기면서 `open_amount`까지 다시 쓰면 원금이 두 번 잡히기 때문이다. `existsByCycleId` 거절은 그 이유가 사라져 제거했다. 사용자 결정(2026-11-09)으로 시드 수정에 종료 사이클 400, 사이클 잠금, 마감 리포트 창 거절을 추가했다.
  - ② 투자 포인트 조회가 이력으로 `restore`한 뒤 계산기에 이력을 넘긴다. 흐름 금액은 원가가 아니라 평가액 변화(예수금 증감 + 수량 증감 × 이력이 걸린 행의 종가)라서 흐름일 수익률이 0이 된다. 날짜는 `occurredAt`의 KST 일자이고, 진입 평가일 이하의 이력은 진입 흐름에 이미 들어 있어 뺀다.
  - ③ BINANCE 사용자 확인(2026-11-09)으로 원인을 다시 판정했다. 700은 V14 배포와 V15 배포 사이(이력 테이블이 생기기 전)에 투자금 추가 API로 넣은 금액이다. 실제 흐름은 10-30 원금 700(장부 전환 전 매수 596.37도 이 돈에서 나감), 11-07 +350, 합계 1,050이다. 그래서 INJECT 700을 백필하는 대신 운영 데이터를 보정했다. 장부 전환 전 행 24개는 예수금 = 700 − 보유 × 평단으로, 전환 후 보정 전 행 5개는 예수금 −596.37로 고쳤다. 이력은 INJECT +350(11-07 02:46 UTC, 보정 시각) 1건만 남기고, `start_amount`는 1,050으로 했다. V17이 아직 운영에 적용되기 전이라 `open_amount`는 배포 때 700으로 채워진다. 기록되지 않은 수수료 3.63은 11-07 손실로 남겼다.
  - 운영 재현: `InvestmentPointsQueryServiceTest`에 보정 전 11-06·11-07 운영 행을 넣어, 11-07 수익률이 −19.4%에서 ≈0%(|r| < 0.0001)로 바뀌는 것을 고정했다.
- 상태: done(641c9dbd5).

### CQ-70 끝난 전략 손익을 곡선에 유지하고 평균 수익률 정의를 문서화한다
- 주제: 통계 정의. 유형: 지표 간 정의 불일치. 위험: 낮음.
- 위치: `TradingStatsService.java:366`(후속 없는 종료 사이클은 종료 다음 날 자산·원금에서 함께 빠진다) ✔, `:289-298`(평균 수익률) ✔.
- 근거: 시드 정책 NONE으로 끝난 전략은 누적 자산추이의 손익이 사라진다. summary와 투자지수에는 남는다. 평균 수익률은 사이클별 단순수익률의 단순평균이고, 분모(현재 `startAmount`)에 후반 추가금이 들어가 희석된다. VR은 N주 구간을 사이클 하나로 센다.
- 수정 방향: 체인 보정과 같은 방식으로, 끝난 전략의 실현손익을 곡선 원금에서 계속 뺀다. 그러면 곡선 마지막 손익이 summary 총손익과 같아진다. 평균 수익률은 정의를 그대로 두고 `modules/tradingcore/stats.md`와 kista-ui 보조문구에 고지한다. CQ-67·68에서 바뀐 정의의 UI 라벨도 이 항목에서 한 번에 맞춘다.
- 가드: 곡선 마지막 점 손익 = summary 총손익(같은 범위) 단위 테스트.
- 상태: open.
