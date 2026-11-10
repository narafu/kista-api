# USD 접미사 체결 금액 개명 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코인 계좌 체결 금액(KRW·USDT)도 지나는 `AccountBalance.Fill.amountUsd()`와 그 짝인 브로커 공용 `Execution.amountUsd`를 통화 중립 `amount`로 개명하고, 낡은 `@Schema` 문구를 고친다.

**Architecture:** 동작 변경 없는 순수 개명이다. API·wire 계약(`com.kista.shared.contract`)·DB·EPR 직렬화 필드에 닿지 않아 kista-ui 계약 변경이 없다. 마감 리포트 전용 USD 타입(`TradeLegSummary`·`TradingReport`)은 유지하고 그 이유를 code-quality 원장 유지 목록에 남긴다.

**Tech Stack:** Java 25, Spring Boot 4, Gradle(`:trading-core`)

**Spec:** 별도 spec 없음 — 2026-11-10 USD 접미사 점검 조사 결과(아래 Background)가 근거다.

## Background (조사 결론)

- (b) 확정: `AccountBalance.Fill.amountUsd()`(`matching/domain/model/AccountBalance.java:43,105`)에 `CryptoLedgerRecorder.java:47`이 KRW/USDT 체결금액을 `ExecutionFillMapper.of(...)`로 넣는다.
- 짝 개명: `Execution.amountUsd`(`broker/domain/model/Execution.java:15`)는 주식 전용이지만 Fill만 바꾸면 `ExecutionFillMapper:17`에 `amountUsd()`→`amount` 매핑이 섞인다.
- 유지: `TradeLegSummary.amountUsd`·`TradingReport.totalBoughtUsd/totalSoldUsd` — 마감 리포트(주식 전용) USD 값이고 통화 필드가 없어 접미사가 유일한 통화 정보, EPR(`TradingReportReadyEvent`) 직렬화 대상. `TradingReporter:83`의 `e.amount()`→`TradeLegSummary.amountUsd` 매핑이 USD 의미가 확정되는 지점이다.
- 그 외 `*Usd` 이름(미리보기 `NextOrdersResponse`, KIS `PresentBalanceResult`·`PortfolioSummaryResponse` 필드, `MarginPort.getUsdBuyableAmount`, 환율·시세 포트)은 코인 경로를 지나지 않아 유지(이번 범위 밖).

## Global Constraints

- 커밋 메시지 한글 + Conventional Commit 접두사, author `narafu <narafu@kakao.com>`, push 금지(사용자 요청 시만).
- 주석은 `//` 인라인만. 기존 주석의 "(USD)" 표기는 개명한 식별자에 한해 통화 중립으로 고친다.
- 리팩토링 커밋은 동작 변경 금지 — 테스트는 이름만 따라 바꾸고 단언 값은 그대로 둔다.
- `--tests` 필터는 `:trading-core:test`로 서브프로젝트를 지정한다. 전체 스위트는 마지막 1회만.

## Review Focus

- 남은 참조: main·test에서 `Fill::amountUsd`·`Execution::amountUsd`·`e.amountUsd()`(Execution 대상)가 0건이어야 한다. 단 `TradeLegSummary.amountUsd`(`TradingReporter:83` 생성자 인자 자리, `TradingReportNotifier:49,51`)는 유지 — grep 시 오인 개명 금지.
- 익명 구현(`BacktestEngine:473`, `AccountBalanceTest:43,53`)은 `@Override` 메서드명까지 바꿔야 컴파일된다 — `compileTestJava`로 확인.
- 문서 드리프트: `docs/agents/modules/shared/kernel.md:8`의 `TradeLegSummary` 설명은 유지 대상이라 손대지 않는다.

---

### Task 1: Fill·Execution 체결 금액 개명

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/AccountBalance.java:43,105`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/Execution.java:15,18`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ExecutionFillMapper.java:17,25`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/OrderFill.java:28`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingReporter.java:83,157,161`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/backtest/BacktestEngine.java:473,506`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossOrderApi.java:146,156,169`
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/AccountBalanceTest.java:37-53`, `.../broker/adapter/out/toss/TossOrderApiTest.java:235,316,318,332`, `.../broker/adapter/out/meritz/MeritzTradingApiTest.java:125`, `.../broker/adapter/out/kis/KisTradingApiTest.java:162`

**Interfaces:**
- Produces: `AccountBalance.Fill#amount(): BigDecimal`(구 `amountUsd()`), `Execution#amount(): BigDecimal`(record 컴포넌트, 구 `amountUsd`), `TossOrderApi#parseAmount(String, BigDecimal, int): BigDecimal`(private, 구 `parseAmountUsd`). `ExecutionFillMapper.ExecutionFill` 컴포넌트 `amount`.

- [ ] **Step 1: 테스트를 새 이름으로 바꾼다** — `AccountBalanceTest`의 익명 `Fill` 두 곳 `@Override public BigDecimal amount()`, 헬퍼 파라미터명 `amount`(지역 변수 충돌 시 인라인); `TossOrderApiTest`·`MeritzTradingApiTest`·`KisTradingApiTest`의 `e.amountUsd()`→`e.amount()`, DisplayName·주석의 `amountUsd`→`amount`. 단언 값은 그대로.

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :trading-core:compileTestJava 2>&1 | grep -E "error:|BUILD"`
Expected: `cannot find symbol ... amount()` 오류와 `BUILD FAILED`

- [ ] **Step 3: main을 개명한다** — Files 목록 순서대로. `Execution` 주석은 `// 체결 금액 (시장 통화) = price × quantity`, 18행 주석의 `amountUsd`→`amount`. `TradingReporter:83`은 `new TradeLegSummary(..., e.amount())`로만 바꾸고 `TradeLegSummary`는 건드리지 않는다. `TossOrderApi` 지역 변수 `amountUsd`→`amount`, 메서드 `parseAmountUsd`→`parseAmount`.

- [ ] **Step 4: 잔여 참조 0건 확인**

Run: `grep -rnE "Fill::amountUsd|Execution::amountUsd|parseAmountUsd|BigDecimal amountUsd\(\)" trading-core/src shared/src`
Expected: 출력 없음. 이어서 `grep -rn "amountUsd" trading-core/src shared/src` 결과가 `TradeLegSummary.java:12`·`TradingReportNotifier.java:49,51`뿐이어야 한다.

- [ ] **Step 5: 관련 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.model.AccountBalanceTest' --tests 'com.kista.tradingcore.broker.adapter.out.*' --tests 'com.kista.tradingcore.trading.application.service.TradingReporterTest' --tests 'com.kista.tradingcore.trading.application.service.CryptoLedgerRecorderTest' --tests 'com.kista.tradingcore.stats.domain.backtest.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL` (`CryptoLedgerRecorderTest`가 없으면 그 필터만 빼고 재실행)

- [ ] **Step 6: Commit**

```bash
git add trading-core/src
git commit -m "refactor(trading-core): 코인 체결도 지나는 체결 금액 amountUsd를 통화 중립 amount로 개명한다"
```

### Task 2: 낡은 문구 정정·유지 판단 기록

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/dto/PortfolioSummaryResponse.java:45-46`
- Modify: `docs/agents/code-quality-backlog.md` "유지 목록" 표(580행~) 끝에 행 추가

**Interfaces:**
- Consumes: Task 1의 `Execution#amount()`(유지 이유 문구에서 참조)

- [ ] **Step 1: `@Schema` 문구 정정** — 45행 description을 `"총자산 (KRW — KIS tot_asst_amt 원본 값)"`, 46행 주석을 `// tot_asst_amt: 총자산 (KRW)`로. 필드명·타입 불변.

- [ ] **Step 2: 유지 목록 행 추가**

```markdown
| `TradeLegSummary.amountUsd`·`TradingReport.totalBoughtUsd/totalSoldUsd` USD 접미사 | `shared/kernel/TradeLegSummary.java:12`, `trading/domain/model/TradingReport.java:13-14` | 마감 리포트(주식 전용) USD 값이고 통화 필드가 없어 접미사가 유일한 통화 정보다. `TradingReportReadyEvent` EPR 직렬화 대상이라 개명엔 `@JsonAlias`가 필요하다. `TradingReporter`의 `Execution.amount()`→`amountUsd` 매핑이 USD 의미가 확정되는 지점이다. 재검토 트리거: 코인 계좌가 마감 리포트·`TradingReportReadyEvent`에 합류할 때 — 그때 `currency` 필드 추가 + 개명 + `@JsonAlias`. (2026-11-10 USD 접미사 점검) |
| 주식 전용 `*Usd` 이름(미리보기·KIS 포트폴리오·`MarginPort.getUsdBuyableAmount`·환율/시세 포트) | `trading/adapter/in/web/dto/NextOrdersResponse.java`, `stats/adapter/in/web/dto/PortfolioSummaryResponse.java`, `broker/application/port/output/MarginPort.java` 외 | 코인 경로를 지나지 않는다(코인 미리보기 제외 `TradingPreviewService:78,105`, 코인 매수가능액은 `UnassignedCashCalculator:63-65`가 `CryptoExchangePort`로 분기, 코인 포트폴리오는 `CryptoPortfolioController`). wire 키라 개명하면 kista-ui 다수 파일만 바뀐다. (2026-11-10 USD 접미사 점검) |
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :trading-core:compileJava 2>&1 | grep -E "error:|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 전체 스위트 1회(최종)**

Run: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit** — 계획 archive 이동은 배포 확인 후 별도 커밋(CLAUDE.md "완료된 계획 archive 이동").

```bash
git add trading-core/src docs/agents/code-quality-backlog.md
git commit -m "docs: 포트폴리오 총자산 설명을 정정하고 USD 접미사 유지 판단을 원장에 기록한다"
```

## kista-ui 인계 (배포 후 UI 세션에 전달)

> kista-api `PortfolioSummaryDto.totalAsset` 필드의 OpenAPI description 문구만 바뀌었다(타입·키 불변). `shared/lib/api-types.ts`를 재생성해 커밋해줘.
