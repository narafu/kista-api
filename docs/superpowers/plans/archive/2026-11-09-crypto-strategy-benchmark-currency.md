# 크립토 전략 STRATEGY 노출 — API 통화 표기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코인 전략을 주식 전략과 같은 STRATEGY 투자지수·벤치마크(ETF·아파트) 비교로 보여줄 수 있게, 투자지수 필드를 통화 중립 이름으로 바꾸고 벤치마크 응답의 투자 통화·안내 문구를 전략 통화로 채운다.

**Architecture:** trading-core `InvestmentPointsQueryService`가 STRATEGY 스코프에서 이미 소유 검증으로 읽는 `Account`의 `broker().getCurrency()`를 결과에 싣고, 내부 계약 `StrategyRefDto` → root `StrategyRef` → 공개 `BenchmarkComparisonResponse.Quality`/notice로 흘린다. PORTFOLIO는 USD 계좌만 합산하므로 `"USD"` 고정을 유지한다.

**Tech Stack:** Java 25, Spring Boot 4, Gradle 3 서브프로젝트(`:api`·`:trading-core`·`:shared`)

**Spec:** 별도 spec 문서 없음 — 2026-11-09 brainstorming 대화에서 승인된 설계(아래 "설계 요약")가 spec이다.

## 설계 요약 (승인됨)

- 목적: 코인 전략 하나의 수익 흐름을 주식 전략과 같은 화면·같은 비교(ETF·아파트 벤치마크)로 본다.
- 비목표: summary·equity-curve·PORTFOLIO 투자지수에 코인 포함, 통화별 분리 집계, 환율 합산 — stats.md "후속" 유지. MOCK 제외 규칙 유지.
- 선행: CQ-67~70(로컬 `main` 미push, 운영 trading flyway V16 — V17 미적용) 배포. 배포 전 UI 필터를 풀면 BINANCE DCA 11-07 −19.4% 왜곡이 보인다.
- 사용자 결정: `investmentIndexUsd` → `investmentIndex` 개명. 배포는 API·UI 연달아 하고 그 사이 짧은 벤치마크 차트 깨짐을 감수한다(expand/contract 하지 않음).
- 내 판단(사용자 승인 대화에서 고지): 사이클 성과 목록에는 `currency`를 추가하지 않는다 — 항목에 `accountId`가 있고 계좌 응답이 이미 `currency`를 준다(kista-ui `entities/account/api/index.ts`). 공개 `StrategyInfo`에도 추가하지 않는다 — `Quality.investmentCurrency`가 같은 값을 싣는다.

## Global Constraints

- 주석은 `//` 인라인만(Javadoc·블록 주석 금지), 신규 필드는 역할 한 줄 주석
- 커밋 메시지 한글 + Conventional Commit 접두사, author `narafu <narafu@kakao.com>`, push 금지(사용자 요청 시만)
- `--tests` 필터는 서브프로젝트 지정(`:test` / `:trading-core:test`)
- 전체 스위트는 마지막 1회만
- `docs/superpowers/**/archive/` 문서는 이력이라 `investmentIndexUsd`를 고치지 않는다
- 공용 통화 enum은 `com.kista.shared.kernel.Currency`(KRW·USD·JPY·USDT) — 새 통화 타입 금지

## Review Focus

1. KRW 코인 계좌(업비트) STRATEGY 아파트 비교 — 투자·벤치마크 둘 다 KRW인데 안내 문구가 "환율 반영 안 함" 류로 모순되지 않아야 한다(Task 2 문구 테스트에 KRW 케이스 포함).
2. STRATEGY인데 데이터 없음(`emptyReason=NO_INVESTMENT_DATA`) — 빈 결과에서도 `Quality.investmentCurrency`가 전략 통화여야 한다(Task 2 테스트).
3. PORTFOLIO 스코프 — `strategy == null`이라 `"USD"`, 기존 문구 그대로(Task 2 테스트).
4. 10분 비교 캐시(`BenchmarkService`) — 키가 전략 id를 포함하므로 통화 추가로 키 변경 불필요. 확인만.
5. 개명 누락 — 공개 OpenAPI 스키마(`HousingBenchmarkApiDocsTest`)와 kista-ui 생성 타입이 같은 이름을 보게 된다. `compileTestJava`로 잔존 참조를 잡는다(grep 아님).

---

### Task 1: `investmentIndexUsd` → `investmentIndex` 개명

**Files (main):**
- `shared/src/main/java/com/kista/shared/contract/stats/InvestmentPointsResponse.java` (`InvestmentPointDto`)
- `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/InvestmentPoint.java`
- `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/StatsContractMapper.java`
- `trading-core/.../stats/application/InvestmentIndexCalculator.java` 등 생성처(컴파일러가 알려줌)
- `src/main/java/com/kista/api/benchmark/domain/model/InvestmentPoint.java`, `BenchmarkPoint.java`
- `src/main/java/com/kista/api/benchmark/application/service/BenchmarkComparisonBuilder.java`, `BenchmarkService.java`
- `src/main/java/com/kista/api/benchmark/adapter/out/internal/InvestmentPointsHttpAdapter.java`
- `src/main/java/com/kista/api/benchmark/adapter/in/web/dto/BenchmarkComparisonResponse.java` (`Point`)
**Files (test):** `InvestmentIndexCalculatorTest`, `InvestmentPointsQueryServiceTest`, `BenchmarkControllerTest`, `HousingBenchmarkApiDocsTest`, `InvestmentPointsHttpAdapterTest`, `BenchmarkServiceTest` + 컴파일러가 알려주는 나머지

**Interfaces:**
- Produces: 레코드 컴포넌트 `investmentIndex`(`BigDecimal`) — 위 모든 record. 주석은 "시점의 누적 투자지수(통화 중립)". JSON 키 `investmentIndex`(내부 API·공개 API 모두).

- [ ] **Step 1:** 모든 main·test 소스에서 `investmentIndexUsd` → `investmentIndex` 개명(지역 변수·메서드 참조 포함, JSON 경로 문자열 `$.points[0].investmentIndexUsd` 포함). archive 문서 제외.
- [ ] **Step 2:** `./gradlew compileTestJava :trading-core:compileTestJava` — BUILD SUCCESSFUL
- [ ] **Step 3:** `grep -rn "investmentIndexUsd" --include=*.java --include=*.yml --include=*.md . | grep -v build/ | grep -v archive/` — 0건
- [ ] **Step 4:** `./gradlew :test --tests 'com.kista.api.benchmark.*' :trading-core:test --tests 'com.kista.tradingcore.stats.*'` — PASS
- [ ] **Step 5:** 커밋 `refactor(stats): 투자지수 필드를 통화 중립 이름 investmentIndex로 바꾼다` (리팩토링 — 동작 변경 없음, JSON 키만 변경됨을 본문에 명시)

### Task 2: 벤치마크 응답의 투자 통화를 전략 통화로

**Files:**
- Modify: `trading-core/.../stats/domain/model/InvestmentPointsResult.java` — 컴포넌트 추가 `Currency strategyCurrency` (STRATEGY만, PORTFOLIO면 null)
- Modify: `trading-core/.../stats/application/service/InvestmentPointsQueryService.java:52-54` — `Account account = accountPort.requireOwnedAccount(...)` 반환값으로 `account.broker().getCurrency()`
- Modify: `trading-core/.../stats/adapter/in/web/StatsContractMapper.java` — `StrategyRefDto`에 통화 전달
- Modify: `shared/.../contract/stats/InvestmentPointsResponse.java` — `StrategyRefDto(UUID id, StrategyType type, StrategyTicker ticker, Currency currency)` (`// 전략 계좌 통화(Broker.getCurrency)`)
- Modify: `src/main/java/com/kista/api/benchmark/domain/model/StrategyRef.java` — `StrategyRef(UUID id, StrategyType type, StrategyTicker ticker, Currency currency)`, 주석 "4필드"로
- Modify: `src/main/java/com/kista/api/benchmark/adapter/out/internal/InvestmentPointsHttpAdapter.java` `toResult` — currency 매핑
- Modify: `src/main/java/com/kista/api/benchmark/adapter/in/web/dto/BenchmarkComparisonResponse.java:35-39,112-114` — Quality·notice
- Docs: `docs/agents/modules/tradingcore/stats.md` "DCA 포함" 절, `docs/agents/modules/api/benchmark.md` getHousingBenchmarkComparison 줄
- Test: `InvestmentPointsQueryServiceTest`, `InvestmentPointsHttpAdapterTest`, `BenchmarkComparisonResponse` 테스트(없으면 `src/test/java/com/kista/api/benchmark/adapter/in/web/dto/BenchmarkComparisonResponseTest.java` 신설)

**Interfaces:**
- Consumes: Task 1의 `investmentIndex`
- Produces: `InvestmentPointsResult.strategyCurrency()`, `StrategyRefDto.currency()`, `StrategyRef.currency()`

**문구 (정확한 카피):**
- 투자 통화 `c` = `strategy == null ? Currency.USD : strategy.currency()`, `Quality.investmentCurrency = c.name()`
- HOUSING: `c == KRW` → `"전략 운용 기록 기반 근사치입니다. 투자 성과와 아파트 매매가격지수 모두 KRW 기준입니다. 벤치마크 시점은 KB Land 주간 조사일(매주 월요일) 기준입니다."`, 그 외 → 기존 HOUSING_NOTICE의 `"USD"`를 `c.name()`으로 치환한 문장
- ETF: `c == USD` → 기존 ETF_NOTICE 그대로, 그 외 → `"전략 운용 기록 기반 근사치입니다. 투자 성과는 " + c + ", ETF 벤치마크는 USD 기준이며 현재 환율은 성과 계산에 반영하지 않습니다."` (구현 중 판단: HOUSING 문구와 같은 표현으로 통일)

- [ ] **Step 1: 실패 테스트 작성**
  - `InvestmentPointsQueryServiceTest.strategy_scope_carries_account_currency`: BINANCE 계좌 전략 STRATEGY 조회 → `result.strategyCurrency() == Currency.USDT`; PORTFOLIO → `null`. (`requireOwnedAccount`는 interface default — 테스트는 default 메서드 자체를 stub할 것)
  - `InvestmentPointsHttpAdapterTest`: 응답 JSON `selectedStrategy.currency:"USDT"` → `result.selectedStrategy().currency() == Currency.USDT`
  - `BenchmarkComparisonResponseTest`(from 정적 메서드):
    - `etf_usdt_strategy` → `quality.investmentCurrency()=="USDT"`, `benchmarkCurrency()=="USD"`, notice contains `"투자 성과는 USDT"`
    - `housing_krw_strategy` → `investmentCurrency()=="KRW"`, notice contains `"모두 KRW 기준"`
    - `portfolio_scope` (strategy null) → `"USD"`, notice equals 기존 ETF 문구
    - `empty_strategy_comparison` (emptyReason `NO_INVESTMENT_DATA`, USDT 전략) → `"USDT"`
- [ ] **Step 2:** `./gradlew :test --tests 'com.kista.api.benchmark.*' :trading-core:test --tests 'com.kista.tradingcore.stats.*'` — 컴파일 실패 또는 FAIL 확인
- [ ] **Step 3:** 위 Files 구현. `StrategyRef` 생성처(테스트 포함)는 컴파일러가 알려준다 — 기존 테스트 픽스처는 `Currency.USD`로 채운다.
- [ ] **Step 4:** 같은 명령 PASS
- [ ] **Step 5:** 문서 갱신
  - stats.md "DCA 포함": "사이클 성과 목록·STRATEGY 스코프는 단일 통화라 포함한다" 뒤에 "STRATEGY 투자지수 응답은 전략 계좌 통화(`Broker.getCurrency()`)를 `selectedStrategy.currency`로 싣고, root 벤치마크가 이를 투자 통화 표기에 쓴다(환율 변환 없음)" 추가
  - benchmark.md: "투자(USD)·벤치마크(HOUSING=KRW/ETF=USD)" → "투자(전략 계좌 통화 — PORTFOLIO는 USD 계좌만 합산해 USD)·벤치마크(HOUSING=KRW/ETF=USD)", `StrategyRef` 설명 "3필드" → "id/type/ticker/currency 4필드"
  - flows.yml·README: `grep -n "investmentIndex\|USD 기준" src/test/resources/architecture/flows.yml README.md` — 걸리는 것만 수정
- [ ] **Step 6:** 전체 스위트 1회 `./gradlew test` — BUILD SUCCESSFUL (실패 시 `grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml`)
- [ ] **Step 7:** 리뷰어(sonnet) 검수 후 커밋 `feat(benchmark): 벤치마크 비교의 투자 통화와 안내 문구를 전략 계좌 통화로 표기한다`

---

## kista-ui 세션 인계 (이 계획 범위 밖)

- 생성 타입 재생성: `investmentIndexUsd` → `investmentIndex` (`entities/stats/lib/housingBenchmarkChartFormatters.ts`, `widgets/benchmark-comparison/HousingBenchmarkChart.tsx` + 테스트), `quality.investmentCurrency` 값이 USDT·KRW일 수 있음
- `widgets/benchmark-comparison/model/useBenchmarkStrategyOptions.ts:27` 코인 제외 해제(MOCK 제외 유지)
- 사이클 성과 목록 금액 포맷: `accountsById.get(accountId).currency`로 `Currency.format`(KRW 정수, USDT 소수 2자리)
- CQ-70 남은 UI 라벨·보조문구
- 배포: API 배포 직후 UI 배포(사이 짧은 차트 깨짐 감수 — 사용자 결정). 둘 다 CQ-67~70 배포 이후.
