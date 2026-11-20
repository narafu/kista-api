# 14 T3 시세 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문 경로의 현재가를 계좌 증권사(`BrokerPricePort.currentPrices`)로, 나머지 시세(전일 종가·확정 종가·일봉·화면 현재가)를 Alpaca(`MarketDataClient`)로 옮기고, 마감 리포트를 마감+20분으로 늦추고, T2 비교 코드를 지운다.

**Architecture:** 값마다 출처 하나, 폴백 체인 없음. `TradingPriceFetcher`는 (계좌, 종목) 키 가격 맵을 만들고 실패를 알림 1건으로 묶는 일만 한다. 확정 종가가 없는 종목의 전략은 리포트하지 않고 배치 단계를 DONE으로 남기지 않는다(재기동 재개가 이어받는다). 옛 포트 메서드·관리자 소스·토스 관리자 코드는 남기고 호출만 끊는다(삭제는 T5).

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + Mockito, `MockRestServiceServer`.

**Spec:** `docs/superpowers/fable-review/guides/14-market-data-provider.md`(11절이 본문보다 우선), `00-roadmap.md` 9-4·9-6, `questions.md` Q-12·Q-13·Q-23·Q-24.

**브랜치:** `n19-t3-market-data`(main에서 분기). main에는 push 대기 문서 커밋이 있어, T3을 main에 커밋하면 문서 push 때 같이 배포된다. 배포는 사용자가 이 브랜치를 main에 합치고 push할 때 한다.

## Global Constraints

- 하지 말 것(14의 9절): 계좌 키로 읽은 현재가를 다른 계좌·화면에 쓰지 않는다(계좌 사이 캐시 공유 금지). 증권사 현재가가 실패해도 Alpaca로 주문 가격을 정하지 않는다. 확정 봉이 없을 때 현재가로 대신하지 않는다. 토스 주문 브로커의 토큰·주문 코드는 고치지 않는다
- T5 범위(옛 `BrokerPricePort` 메서드 7개, `AdminMarketPricePort`·`AdminMarketPriceAdapter`·`AdminPriceSource`, `CandlePort`, `BrokerRouter` 관리자 우선 경로, `TossPriceApi`·`TossCandleApi`·`TossAdminPriceSource`, 설정 `toss.admin-*`)는 **지우지 않는다**. 소비처만 옮긴다
- Alpaca 메서드의 날짜 인자는 미국 세션일이다. KST 거래일은 `MarketCalendarPort.usSessionDateOf(kstTradeDate)`로 바꾼다. `minusDays(1)` 직접 계산 금지
- 어댑터는 `UsTradeDates`를 쓰지 않는다(가드 `usTradeDates_must_only_be_used_by_allowlisted_adapters`)
- 새 단일 구현 인터페이스 금지(가드 `interfaces_must_earn_their_keep`)
- 로그 접두사 `[strategyId={}]`·`[accountId={}]`, IAE/ISE 메시지는 "~니다."로 끝(가드 `ExceptionMessageFormatTest`), 신규 코드에 한 줄 주석
- 서브에이전트: git add/commit 하지 말고 파일만 수정. 빌드·테스트는 `2>&1 | grep -E "FAILED|BUILD|ERROR|Test.*>"`로 거른다
- 태스크 반복 검증(매 태스크 공통): `./gradlew :trading-core:test --tests '<바꾼 테스트들>' 2>&1 | grep -E "FAILED|BUILD"`, `./gradlew :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`, `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`. flows.yml `code:`를 건드린 태스크는 `ArchitectureMapTest`도
- 전체 `./gradlew test`는 Task 7에서 1회

## Review Focus

1. 두 계좌가 같은 종목을 가질 때 한 계좌의 현재가만 실패 → 그 계좌의 그 종목 전략만 빠지고 다른 계좌는 자기 값으로 주문(Task 2 테스트)
2. 마감 리포트 시각에 확정 봉이 없음 → 원장에 null 종가가 저장되지 않고 DONE이 남지 않음, 다음 재개에서 그 전략만 리포트(Task 3 테스트)
3. 뉴욕 16:00~16:16(KST 05:00~06:16 근처)에 전일 종가를 읽음 → 진행 중인 당일 봉이 "전일 종가"로 하루 캐시되지 않음(Task 1 테스트). 22:00 개장 배치의 INFINITE 0회차 진입가가 여기서 온다
4. 예전에 예외로 실패를 알리던 호출부가 새 API의 "키 없음"을 null로 받아 조용히 진행(신규 등록 startAmount·VR V, VR 롤오버) → `require*` 헬퍼가 예외로 바꾼다(Task 1·4 테스트)
5. 조기 마감일(11-27, 뉴욕 13:00 마감) → 리포트가 closeAt+20분(KST 03:20)에 돈다(Task 3 테스트)

---

### Task 0: 브랜치

- [ ] `git switch -c n19-t3-market-data` (main `2036509c4` 기준). 직접 한다(위임 안 함)

### Task 1: `MarketDataClient` 이름 변경, 전일 종가 미완결 봉 차단, 공용 헬퍼

모델: 구현 Sonnet, 리뷰 Opus(INFINITE 진입가 입력)

**Files:**
- Rename: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/marketdata/AlpacaMarketDataAdapter.java` → `MarketDataClient.java`(패키지·`@NamedInterface("marketdata")`·`AlpacaMarketDataConfig`는 그대로)
- Modify: 참조처 전부(`PriceSourceComparison`, `CycleOrderComputer`, 테스트) — `git grep -l AlpacaMarketDataAdapter`
- Test: 기존 어댑터 테스트 클래스를 `MarketDataClientTest`로 이름 변경하고 케이스 추가

**Interfaces (Produces):**
- `public Map<StrategyTicker, BigDecimal> latestCloses(Collection<StrategyTicker> tickers)` — "지금 기준 마지막 확정 종가". `closesBefore(tickers, nyToday.plusDays(1))`(nyToday = `LocalDate.now(clock.withZone(TimeZones.NY))`). 배치 밖 호출부(등록·미리보기·이력·통계)의 공용 규칙
- `public BigDecimal requireLatestClose(StrategyTicker ticker)` — 키 없으면 `IllegalStateException(ticker.name() + " 종가를 조회하지 못했습니다.")`
- `public BigDecimal requireConfirmedClose(StrategyTicker ticker, LocalDate usSessionDate)` — `confirmedBars`의 close, 없으면 같은 형식의 ISE
- `closesBefore` 동작 보정: 뉴욕 현재 시각이 오늘 16:16 전이면 **날짜가 뉴욕 오늘인 봉은 고르지 않는다**. 그 경우(`usSessionDate`가 뉴욕 오늘 뒤이고 16:16 전) 결과를 캐시하지 않는다(TTL `Duration.ZERO`). `BAR_SETTLED_AT` 상수 재사용

- [ ] **Step 1: 실패 테스트 작성** (`MockRestServiceServer`, 응답 JSON은 기존 테스트의 bars 형태)
  - `closesBefore_뉴욕_마감_정산_전에는_당일_봉을_전일_종가로_쓰지_않는다`: clock = 2026-11-20 15:50 America/New_York, `closesBefore([SOXL], 2026-11-23)`, 응답 bars 11-19 c=58.32·11-20 c=49.19 → 58.32
  - `closesBefore_정산_전_결과는_캐시하지_않는다`: 같은 시각 1회 호출 → clock을 16:20으로 옮겨 재호출 → 서버 2회 호출, 두 번째 결과 49.19
  - `latestCloses_뉴욕_다음날을_세션일로_쓴다`: clock 2026-11-20 17:00 NY, 응답 11-19·11-20 → 11-20 값
  - `requireLatestClose_키가_없으면_예외`: 빈 bars → `IllegalStateException`, 메시지 "SOXL 종가를 조회하지 못했습니다."
  - `requireConfirmedClose_봉이_없으면_예외`
- [ ] **Step 2:** 테스트 실패 확인 — `./gradlew :trading-core:test --tests '*MarketDataClientTest' 2>&1 | grep -E "FAILED|BUILD"`
- [ ] **Step 3:** 이름 변경 + 위 메서드·보정 구현
- [ ] **Step 4:** 통과 확인 + 변이 확인 2개: 당일 봉 필터를 지우면 첫 테스트가, TTL 분기를 지우면 둘째 테스트가 실패하는지(확인 후 원복)
- [ ] **Step 5:** 공통 아키텍처 검증, 리뷰, 커밋 `refactor(broker): Alpaca 시세 어댑터를 MarketDataClient로 바꾸고 미정산 당일 봉을 전일 종가에서 뺀다`

### Task 2: `TradingPriceFetcher`를 계좌별 현재가 + Alpaca 전일 종가로, T2 비교 코드 삭제

모델: 구현 Opus, 리뷰 Opus

**Files:**
- Create: `trading-core/.../trading/application/service/PriceKey.java`
- Modify: `TradingPriceFetcher.java`(전면), `TradingService.java`(117·118·121·157·158·168-170·201-203·270·277·297·355·356·382·396·399·408행 부근), `TradingCandidatePlanner.java`(`planAll`·`collectOpenCandidates`·`collectCycleCandidate`의 가격 맵), `TradingPreviewService.java:127`, `ManualTradingService.java:145`(호출 그대로인지 확인만)
- Delete: `PriceSourceComparison.java`와 그 테스트
- Modify: `src/test/resources/architecture/flows.yml` `code:` 115·312행(`TossAdminPriceSource` → `BrokerPricePort#currentPrices, MarketDataClient#closesBefore`). 169·185·357·640행은 메서드 이름이 그대로면 유지
- Test: `TradingPriceFetcherTest`(재작성), `TradingServiceTest`·`TradingCandidatePlannerTest`·`TradingPreviewServiceTest` 등 컴파일되게 수정

**Interfaces:**
- Consumes: `MarketDataClient.closesBefore`, `latestCloses`, `confirmedBars`(Task 1), `BrokerPricePort.currentPrices(BrokerAccountRef, Collection<StrategyTicker>)`, `MarketCalendarPort.usSessionDateOf(LocalDate)`
- Produces:
  - `record PriceKey(UUID accountId, StrategyTicker ticker) { static PriceKey of(BatchContext ctx) }` (package-private)
  - `record PriceContext(List<StrategyTicker> cycleTickers, Map<PriceKey, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase)` + `withPrivacyBase` (`priceAccount` 삭제)
  - `PriceContext loadPriceContext(List<BatchContext> contexts, LocalDate kstTradeDate)`
  - `PrivacyTradeBase privacyBaseFor(List<BatchContext> contexts, LocalDate kstTradeDate)` — `loadPriceContext`와 `reportRebuilt`가 공유
  - `Map<PriceKey, BigDecimal> reloadPlacementPrices(List<TradingCandidatePlanner.CycleState> states)`
  - `Map<StrategyTicker, BigDecimal> fetchPrices(List<StrategyTicker> tickers, BrokerAccountRef account)` — `currentPrices` 1회, 누락은 알림
  - `Map<StrategyTicker, BigDecimal> fetchPrevCloses(List<StrategyTicker> tickers)` — `latestCloses`, 알림 없음(지금 주석의 readOnly 이유 유지)
  - `Map<StrategyTicker, BigDecimal> fetchClosingPrices(List<StrategyTicker> tickers, LocalDate kstTradeDate)` — `confirmedBars(tickers, usSessionDateOf(kstTradeDate))`의 close, 키 없음 그대로 반환, **알림 없음**(Task 3이 보류 알림을 낸다)
  - 플래너: `planAll(List<BatchContext>, Map<PriceKey, PriceSnapshot>, PrivacyTradeBase, LocalDate)`, `collectOpenCandidates(...)`도 같은 맵 타입. 조회는 `startPriceSnapshots.get(PriceKey.of(ctx))`

동작 규칙(현재가):
- 계좌별로 그 계좌 전략의 종목만 모아 `currentPrices`를 부른다. 같은 종목이라도 계좌마다 따로 부른다. 계좌 사이는 `TradingParallelRunner`(groupKey = 계좌 id)로 병렬
- 계좌 호출이 예외면 그 계좌 종목 전부 실패(`log.warn("[accountId={}] 현재가 조회 실패: {}", id, e.getMessage())`), 키 없음·null도 실패. 실패 키는 맵에서 빠진다(플래너는 이미 키 부재를 skip한다)
- 다른 출처(`latestPrices`)로 대체하지 않는다
- 알림은 배치당 `TradingErrorEvent` 1건: `"현재가 조회 실패: 계좌 N개 [SOXL, TQQQ]"`와 `"[USD] 전일종가 조회 실패(현재가로 대체)"`를 `", "`로 잇는다

동작 규칙(전일 종가): `closesBefore(cycleTickers, usSessionDateOf(kstTradeDate))` 1회. 예외면 전 종목 prevFailed(catch는 분기가 있으니 raw catch 허용), 키 없음도 prevFailed. 스냅샷의 prevClose가 없으면 그 계좌 현재가로 대체(지금 동작 유지).

`TradingService`: 가격 조회를 `PriceKey.of(state.ctx())`로, `placeAll`·`planAndPlaceAtOpen`의 `startPrice` 폴백은 유지(같은 계좌·같은 출처). `priceComparison` 필드와 호출 5곳, `usSessionDate` 헬퍼 삭제. `reportRebuilt`는 현재가를 읽지 않는다: 종목은 `targets`에서, 기준표는 `privacyBaseFor`, 종가는 `fetchClosingPrices(tickers, today)`.

`selectPriceAccount`·`fetchWithFallback` 삭제(Q-23의 대상이 사라진다 — Task 6에서 기록).

- [ ] **Step 1: 실패 테스트 작성** (`TradingPriceFetcherTest`, Mockito로 `BrokerPricePort`·`MarketDataClient`·`MarketCalendarPort` mock)
  - `같은_종목을_가진_두_계좌_중_한_계좌만_현재가가_실패하면_그_계좌_키만_빠진다`: 계좌 A·B 모두 SOXL, A의 `currentPrices` 예외 → 맵에 `PriceKey(B,SOXL)`만, `TradingErrorEvent` 1건이고 메시지에 "계좌 1개"
  - `계좌마다_자기_전략_종목만_조회한다`: A=SOXL, B=TQQQ → `currentPrices(A.ref, [SOXL])`, `currentPrices(B.ref, [TQQQ])` 각 1회, 그 밖의 인자로는 0회
  - `현재가_실패를_다른_출처로_대체하지_않는다`: A 실패 → `marketData.latestPrices` 호출 0회
  - `전일종가는_KST_거래일의_미국_세션일로_조회한다`: `usSessionDateOf(2026-11-24)` → 2026-11-23 stub, `closesBefore(any, 2026-11-23)` 검증
  - `전일종가가_없으면_그_계좌_현재가로_대체하고_알림을_1건_보낸다`
  - `현재가와_전일종가가_모두_실패해도_알림은_1건이다`
  - `접수_직전_재조회도_계좌별이다`(`reloadPlacementPrices`)
  - `확정종가는_세션일로_조회하고_없는_종목은_키가_없다`(`fetchClosingPrices`, 알림 0건)
  - 폴백 단계 테스트(관리자 → 계좌 → 단건)는 삭제
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3:** 구현 + 호출부 수정 + `PriceSourceComparison` 삭제 + flows.yml `code:`
- [ ] **Step 4:** `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' 2>&1 | grep -E "FAILED|BUILD"` + 공통 아키텍처 검증 + `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`. 변이 확인: 계좌 그룹핑을 "첫 계좌로 전 종목 조회"로 바꾸면 첫 두 테스트가 실패하는지
- [ ] **Step 5:** 리뷰, 커밋 `feat(trading): 주문 경로 현재가를 계좌 증권사에서, 전일 종가를 공용 시세에서 읽는다`

### Task 3: 마감 리포트 +20분, 확정 종가 없음 → 리포트 보류

모델: 구현 Opus, 리뷰 Opus

**Files:**
- Modify: `trading/domain/model/DstInfo.java`(`postCloseTime` 05:10/06:10 → 05:20/06:20, `forCloseSession` +10 → +20, 레코드·메서드 주석), `trading/domain/model/TradingBatchStep.java`(`REPORT_DELAY` 10 → 20, 21·27행 주석), `TradingService.java`(`executeBatch` 마감부, `reportAll`, `reportPausedStrategies`, `reportRebuilt`, `resumeCloseReport`, `finishWithPausedReport`)
- Test: `DstInfoTest`, `TradingBatchStepTest`(기대 시각), `TradingServiceTest`(또는 마감 리포트 테스트가 있는 클래스)

**Interfaces:**
- Consumes: `TradingPriceFetcher.fetchClosingPrices(tickers, kstTradeDate)`(Task 2)
- Produces: `reportAll`·`reportPausedStrategies`·`reportRebuilt`가 `boolean`(전부 리포트했으면 true)을 돌려준다. `static final Duration CLOSE_RETRY_DELAY = Duration.ofMinutes(10)`

동작 규칙:
- `reportAll`은 종목의 종가가 없으면 그 전략을 리포터에 넘기지 않는다(`log.warn("[strategyId={}] 확정 종가 없음 — 마감 리포트를 미룹니다", id)`). 보류가 있으면 배치당 `TradingErrorEvent` 1건: `"[마감 리포트 보류] [TQQQ] 확정 종가 없음 — 재기동하면 리포트를 재개합니다"`
- 보류가 하나라도 있으면 `checkpoint(CLOSE, today, DONE)`을 쓰지 않는다(단계는 PLACED로 남는다). 정상 마감 경로·`resumeCloseReport`·`finishWithPausedReport` 모두
- 정상 마감 경로만 1회 재조회: 마감 대기 뒤 임계구역 **밖에서** 종가 조회 → 빠진 종목이 있으면 `waitFor("확정 종가 재조회", CLOSE_RETRY_DELAY, dst)` 뒤 빠진 종목만 다시 조회해 합친다 → 임계구역에서 리포트. 재개 경로는 재조회 없이 1회
- 테스트는 실제 10분을 자지 않는다. `waitFor`를 기존 테스트가 우회하는 방식을 따르고, 없으면 `CLOSE_RETRY_DELAY`를 생성자 주입 대신 package-private 필드로 두어 테스트가 `Duration.ZERO`로 바꾼다

- [ ] **Step 1: 실패 테스트 작성**
  - `DstInfoTest.forCloseSession_리포트는_마감_20분_뒤`: close 2026-11-24T21:00Z → postClose 21:20Z. `조기_마감일도_마감_20분_뒤`: close 2026-11-27T18:00Z(뉴욕 13:00) → 18:20Z. `calculate`의 postClose가 비DST 06:20·DST 05:20 KST
  - `TradingBatchStepTest`: `CLOSE_REPORT` 창 시작 기대값을 +20분으로
  - `확정_종가가_없는_종목의_전략은_리포트하지_않고_DONE을_남기지_않는다`: SOXL 전략·TQQQ 전략, 두 번의 조회 모두 TQQQ 없음 → 리포터는 SOXL 전략만, `checkpoint(..., DONE)` 0회, 보류 알림 1건
  - `재조회에서_종가가_오면_리포트하고_DONE을_남긴다`: 1차 TQQQ 없음, 2차 있음 → 리포터 2회, DONE 1회
  - `보류된_전략은_리포트_재개에서_그_전략만_리포트한다`: `isReported`가 SOXL 전략 true·TQQQ 전략 false, 종가 있음 → `resumeCloseReport`가 TQQQ 전략만 리포트하고 DONE
  - `리포트_재개에서도_종가가_없으면_DONE을_남기지_않는다`
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3:** 구현
- [ ] **Step 4:** 통과 + 변이 확인: 보류 분기를 지우면(null 종가로 리포트) 첫 리포트 테스트가 실패하는지
- [ ] **Step 5:** 공통 검증, 리뷰, 커밋 `feat(trading): 마감 리포트를 마감 20분 뒤로 옮기고 확정 종가가 없으면 리포트를 미룬다`

### Task 4: 나머지 trading 소비처

모델: 구현 Sonnet, 리뷰 Opus

**Files / 바꿀 호출** (줄은 2026-11-20 기준):
- `StrategyCreationService.java:146` `getPrevClose` → `marketData.requireLatestClose(ticker)`(기존 `BrokerCallGuard` 감싸기 유지)
- `InfiniteMinSeedGuard.java:31` → `marketData.requireLatestClose(ticker)`(기존 `tryGet(...).orElse(null)` 유지)
- `StrategyOrderPlanBuilder.java:72` → `requireLatestClose`
- `support/StrategyHistoryQueryService.java:77` → `requireLatestClose`
- `VrCycleRolloverService.java:84` → `marketData.requireConfirmedClose(ticker, marketCalendarPort.usSessionDateOf(evaluationDate))`(기존 catch·알림·다음 매매일 재시도 유지)
- `AccountLedgerTotalsService.java:51` `getPrices` → `marketData.latestPrices(tickers)`(화면용, 기존 `tryGet` 유지)
- `VrReconfigureService.java:98` `getPrice` → `brokerPricePort.currentPrices(account.toBrokerRef(), List.of(ticker)).get(ticker)`, null이면 `IllegalStateException("현재가를 조회하지 못했습니다.")`(주문을 내는 경로라 계좌 증권사)
- `CycleOrderComputer.java:130` `tradeDate.minusDays(1)` → `marketCalendarPort.usSessionDateOf(tradeDate)`
- `DcaPlanInputCollector.java:142` `candlePort.getDailyCandlesBefore(name, usSession, CANDLE_COUNT)` → `marketData.dailyCandles(name, DailyCandleQuery.lastBefore(usSession, CANDLE_COUNT, DailyCandleQuery.Adjustment.ALL))`. 반환 순서·개수 의미가 같은지 기존 테스트로 확인
- `ManualTradingService.java:145`: Task 2 뒤 그대로 `fetchPrices`(계좌 증권사)인지 확인만
- 생성자에서 쓰지 않게 된 `BrokerPricePort`·`CandlePort` 필드는 지운다

별지점 트랜잭션(14의 11절 2026-11-16 보정): 배치 계획(`TradingCandidatePlanner` → `CycleOrderComputer.compute`)은 트랜잭션 밖이고(2026-11-20 grep: planner·computer에 `@Transactional`·`TransactionOperations` 없음) readOnly 트랜잭션은 미리보기(`TradingPreviewService:70,87`)뿐이다. 미리보기는 전일 종가 조회와 같은 기존 구조다. 구현자는 `execute*`·`@Transactional` 호출 사슬을 한 번 더 확인하고, 배치 경로가 트랜잭션 밖이면 코드를 옮기지 않고 Task 6에서 11절에 "대상 없음"을 적는다. 트랜잭션 안이면 멈추고 보고한다.

- [ ] **Step 1: 테스트 수정·추가** — 각 서비스 테스트의 mock을 새 의존으로 바꾸고, 키 없음 케이스를 하나씩:
  - `StrategyCreationServiceTest`: `requireLatestClose`가 ISE → 등록 거부(지금 `getPrevClose` 예외일 때와 같은 응답·예외 타입)
  - `VrCycleRolloverServiceTest`: `requireConfirmedClose` ISE → 롤오버 없음, `TradingErrorEvent` 1건. 세션일 변환 검증(`usSessionDateOf(evaluationDate)` 결과로 호출)
  - `VrReconfigureServiceTest`: `currentPrices` 빈 맵 → ISE "현재가를 조회하지 못했습니다."
  - `CycleOrderComputerTest`(또는 별지점 테스트): `dailyCandles`가 `usSessionDateOf(tradeDate)` 결과 날짜로 불리는지
  - `DcaPlanInputCollectorTest`: `dailyCandles(…, lastBefore(usSession, 250, ALL))` 검증
- [ ] **Step 2:** 실패 확인 → **Step 3:** 구현 → **Step 4:** `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' 2>&1 | grep -E "FAILED|BUILD"` + 공통 아키텍처. flows.yml 516행 `BrokerPricePort#getPrevClose` → `MarketDataClient#requireLatestClose`, `ArchitectureMapTest`
- [ ] **Step 5:** 리뷰, 커밋 `refactor(trading): 등록·미리보기·롤오버·DCA 지표의 시세를 공용 시세로 옮긴다`

### Task 5: 차트 일봉·stats 통합, Mock·토스 어댑터

모델: 구현 Sonnet, 리뷰 Opus(Mock 체결 판정)

**Files:**
- `broker/adapter/in/web/CandleInternalController.java`: `candlePort` → `marketData.dailyCandles(symbol, DailyCandleQuery.latest(count, adj))`·`range(from, to, adj)`. 조정 방식은 지금 `TossCandleApi` 호출 인자와 같게(14의 9절 기본값). `interval` 인자를 이 컨트롤러와 root 중계(`src/main/java/com/kista/api/market/application/port/output/CandleQueryPort.java`와 그 HTTP 어댑터·root 컨트롤러)에서 지운다. `shared.contract` 타입이 바뀌면 `InternalApiContractTest`. 화면 숫자가 달라질 수 있으면 `cross-repo.md`에 한 줄
- stats: `BacktestService`·`AccountStatisticsService`가 `HistoricalCandlePort` 대신 `MarketDataClient.dailyCandles`(백테스트의 `to` 클램프는 `DailyCandleQuery.range`로). `AccountStatisticsService.java:87` `getPrevCloses` → `marketData.latestCloses(tickers)`(키 부재 처리 그대로). 삭제: `stats/adapter/out/alpaca/AlpacaCandleAdapter.java`, `stats/application/port/output/HistoricalCandlePort.java`와 테스트, `stats/application/port/output/package-info.java`의 해당 언급
- `broker/adapter/out/mock/MockBrokerAdapter.java`: `TossPriceApi priceFeed` → `MarketDataClient marketData`. 현재가·스냅샷 → `latestPrices`, 전일 종가 → `latestCloses`, 확정 종가·`getExecutions` 봉 → `confirmedBars(ticker, usSession)`. KST 거래일 → 세션일은 `MarketCalendarPort.usSessionDateOf`(broker → marketcalendar 의존이 Modulith에서 막히면 멈추고 보고). `getExecutions`의 "확정 봉 없으면 현재가 단일값 봉"(140-142행) 삭제 → `IllegalStateException("확정 일봉이 없습니다.")`. `currentPrices` override → `latestPrices`
- `broker/adapter/out/toss/TossBrokerAdapter.java`: `currentPrices` override → `marketData.latestPrices(tickers)`(Q-13 대체 경로. 사용자가 로컬 1회 호출로 계좌 토큰 시세가 되는 것을 확인하면 이 줄만 계좌 토큰 호출로 바꾸는 후속을 따로 낸다)

- [ ] **Step 1: 테스트**
  - `MockBrokerAdapterTest`: `확정_일봉이_없으면_체결_조회가_예외`(현재가 호출 0회), `LIMIT_매수는_확정_일봉_저가로_판정`(기존 케이스를 `confirmedBars` stub으로), `currentPrices는_공용_현재가`
  - `TossBrokerAdapterTest`: `currentPrices는_공용_현재가이고_토스_시세_API를_부르지_않는다`
  - `CandleInternalControllerTest`(있으면): `dailyCandles` 인자 검증
  - 백테스트·통계 테스트는 mock 교체로 컴파일·통과
- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' --tests 'com.kista.tradingcore.stats.*' 2>&1 | grep -E "FAILED|BUILD"`, root market 테스트, 공통 아키텍처
- [ ] **Step 5:** 리뷰, 커밋 `refactor(broker): 차트·백테스트·Mock 시세를 공용 시세로 옮기고 Mock 체결의 현재가 대체를 지운다`

### Task 6: 문서

모델: Sonnet(문서 전용이라 리뷰 생략). 한 디스패치로 묶는다

- `docs/agents/workflow-ledger-price.md` "시세 출처": 값별 출처 표(14의 3-5절 표), 확정 종가 없음 → 리포트 보류
- `docs/agents/modules/tradingcore/broker.md`: `MarketDataClient`, `BrokerPricePort.currentPrices`가 주문 경로 유일 현재가, 옛 메서드는 T5 삭제 대기
- `docs/agents/vendors/toss.md`: "토스 계좌 현재가는 공용 시세(Alpaca)다. 토스 전략을 등록하기 전에 계좌 토큰 시세 호출을 확인한다"(Q-13)
- `docs/agents/scheduler-time-table.md`·`README.md`: 마감 리포트 05:20/06:20 KST(조기 마감일 closeAt+20)
- `flows.yml` 서술(`desc`·`fail`): 가격 컨텍스트·마감 리포트 시각·보류
- `questions.md`: Q-23 "답함 — T3에서 `fetchWithFallback` 삭제로 대상 없음". 신규 Q-30 "확정 종가 없음 보류 — 06 전에는 재기동만 재개한다, 1회 재조회(+10분)"(선택지: ① 재기동 재개 + 1회 재조회(채택) ② null 종가 저장(지금 동작) ③ reconcile에 리포트 재실행 추가(06 T2 몫)). Q-13 상태에 T3 대체 경로 적용을 적는다
- `14-market-data-provider.md` 11절 끝에 "T3 실행 결과"(별지점 트랜잭션 대상 없음 여부, `closesBefore` 미정산 봉 보정, `latestCloses`·`require*`, 토스 대체 경로)
- 커밋 `docs: 14 T3 시세 전환을 문서에 맞춘다`

### Task 7: 최종 검증, 배포 전 확인표, 관찰 계획, 로드맵

직접 한다(운영 조회·판단). 비교 계산만 Sonnet에 위임 가능

- [ ] `./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"` 1회. 실패는 `grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml`
- [ ] 다음 거래일 주문 비교표(9-4 "14 T3" 행): 운영 읽기 전용 SELECT로 진행 중 전략 전부(id 앞 8자, 타입, 종목, 계좌 증권사)를 뽑는다. 커널이 바뀌지 않으므로 worktree 없이, 커밋하지 않는 임시 테스트로 같은 커널에 입력 가격 두 벌(구: 11-20 배치의 `[시세 비교]` old, 신: new)을 넣어 다음 거래일 주문을 비교한다. 차이는 가격 입력에서만 나온다는 것을 표 아래에 적는다
- [ ] 배포 직전 SELECT와 기대값: SUBMITTING 0, 미완료 EPR 0(두 테이블), `trading_batch_run` 오늘 CLOSE DONE
- [ ] 로드맵 9-4 배포 전 확인표(N19 칸): T2 표(Q-24 대조값), "분할 공시가 있는 주가 아님"(SOXL·TQQQ·USD·QLD·UPRO·BITO·EETH 배포 주 확인), **선행: kista-infra `trading-guard.sh` 마감 창 상한 06:20 → 06:30(cross-repo G7에 요청 적기, 완료 확인 전 배포 금지)**, KST 07:00~16:00, N9와 다른 배포, N1 전환 주(11-16~20)와 다른 주, N1 관찰(11-23·24) 통과 뒤
- [ ] 관찰 계획(2거래일, 전환 전날과 대조): 접수 주문의 가격·수량(전략별), `[accountId=…] 현재가 조회 실패` 0건, 마감 리포트 시각 closeAt+20분(조기 마감일 11-27은 KST 03:20), `cycle_position.closing_price`와 공식 종가 일치·null 0건, `[마감 리포트 보류]` 0건, 관리자·사용자 알림 건수, 메리츠 429·`BrokerRateLimitException` 0건, ERROR 0. 명령은 `ssh kista-server "docker logs kista-worker --since 24h 2>&1 | grep -E '…'"`
- [ ] 로드맵 N19 칸 갱신(커밋 해시, 검증 결과, 배포 전 확인표, 관찰 대기), `cross-repo.md` G7 요청. 계획 파일은 T3 배포·관찰이 끝나면 지운다(지금은 유지)
- [ ] 커밋 `docs(fable-review): N19 T3 구현 완료와 배포 전 확인표를 적는다`
