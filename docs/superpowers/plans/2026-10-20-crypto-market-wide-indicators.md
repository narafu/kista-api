# 코인 시장 전체 지표(BTC 도미넌스·알트코인 시즌·Hash Ribbons) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/crypto-market-indicators` 응답 최상위에 `btcDominance`·`altcoinSeason`·`hashRibbons` 세 섹션을 추가한다.

**Architecture:** trading-core `stats` 모듈. CMC(공식 Pro API + 비공식 웹 API) 신규 어댑터, 기존 mempool 어댑터에 해시레이트 메서드 추가, Hash Ribbons는 domain 순수 계산(`CryptoValuations` 류), `CryptoMarketIndicatorService.assemble()`이 `guard`로 섹션별 실패 격리. 이력 3종은 같은 `TtlResultCache`에 다음 UTC 자정까지 캐시.

**Tech Stack:** Java 25, Spring Boot 4 `RestClient`, `MockRestServiceServer`, JUnit5/AssertJ/Mockito.

**Spec:** `docs/superpowers/specs/2026-10-19-crypto-market-indicators-design.md` — "### 시장 전체 지표 추가 — BTC 도미넌스·알트코인 시즌·Hash Ribbons (2026-10-20)" 섹션

## Global Constraints

- 주석: `//` 인라인만(Javadoc·블록 주석 금지), 필드마다 역할 한 줄, 로직 블록 직전 한 줄 (`docs/agents/constraints.md` "주석 규칙")
- CMC 키는 코드·테스트·커밋에 절대 넣지 않는다. `cmc.api-key: ${CMC_API_KEY:}`(기본값 빈 문자열, 필수 아님). 테스트는 가짜 키 `"test-key"`
- 키가 비면 HTTP 호출 없이 `IllegalStateException` (크레딧 소모 없음, 기동 실패 아님)
- 조회 길이 `MAX_DAYS`(365) 고정, 응답 `days`는 `withDays`로 자른다. 세 이력 모두 오늘(UTC) 점 제외
- 날짜 키는 UTC (`Instant.ofEpochSecond(..).atZone(ZoneOffset.UTC).toLocalDate()`) — 기존 Binance·Coinbase와 같은 명시적 예외
- 응답 필드명: `btcDominance{current, history[{date, value}]}`, `altcoinSeason{current, history[{date, value}]}`, `hashRibbons{current{date, hashrateEh, sma30, sma60, state, buySignal}, history[...]}`
- 모든 응답 DTO 속성 `requiredMode = REQUIRED`, 스키마명 `CryptoMarketIndicators*` 접두사, BigDecimal은 `PlainDecimalSerializer`
- 테스트 실행은 범위 한정 + 필터: `./gradlew :trading-core:test --tests '<패턴>' 2>&1 | grep -E "FAILED|BUILD|ERROR|Test.*>"`. 전체 스위트는 마지막 Task에서 1회
- 커밋: 한글 Conventional Commit, author `narafu <narafu@kakao.com>`, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01UkYd3qpDPzCsumFFAD2Tp1`. push 금지

## Review Focus

1. CMC가 HTTP 200 + `status.error_code != 0`(크레딧 소진 1008/1009, 키 무효 1001)을 주는 경우 → NPE가 아니라 `error_message` 담은 `IllegalStateException` (Task 2 테스트)
2. 키 빈 문자열 → HTTP 요청 0건(MockRestServiceServer 기대 없음으로 검증), 섹션 null, 다른 섹션 정상 (Task 2·3 테스트)
3. 비공식 알트 API 형식 변경(필드 누락·문자열 파싱 실패) → `altcoinSeason.current` 유지 + `history: []`, 다른 섹션 무영향 (Task 3 테스트)
4. 이력 조회 실패는 자정까지 캐시되지 않고 다음 조립에 재시도, 성공은 10분 넘어도 재호출 없음 (Task 3 테스트)
5. mempool/CMC 이력에 오늘(UTC) 점이 섞여 오는 경우 → 응답 history에 오늘 날짜 없음 (Task 1·3 테스트)

---

### Task 1: Hash Ribbons 순수 계산

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoHashRibbons.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/DatedHashRibbon.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/domain/model/CryptoHashRibbonsTest.java`

**Interfaces:**
- Produces:
  - `public record DatedHashRibbon(LocalDate date, BigDecimal hashrateEh, BigDecimal sma30, BigDecimal sma60, State state, boolean buySignal)` + 중첩 `public enum State { CAPITULATION, RECOVERY }` — `sma30`/`sma60`/`state` nullable
  - `public final class CryptoHashRibbons { public static List<DatedHashRibbon> history(List<DatedValue> dailyHashratesEh, LocalDate today, int days) }` — 오래된 순

- [ ] **Step 1: 실패 테스트 작성** (`CryptoHashRibbonsTest`, 헬퍼 `series(LocalDate start, double... values)`로 연속 일자 생성)
  - `이력_60일_미만_일자는_state_null_buySignal_false`: 59개 100 → 전부 `sma60 == null`, `state == null`, `buySignal == false`; 59번째 `sma30` = 100.00
  - `sma30이_sma60과_같으면_RECOVERY`: 60개 100 → 마지막 점 `sma30 == sma60 == 100.00`, `state == RECOVERY`, `buySignal == false`
  - `하락하면_CAPITULATION_회복_교차일만_buySignal`: 60개 100 + 20개 50 + 40개 200 → 인덱스 79(50의 마지막 점) `state == CAPITULATION`; 이후 `RECOVERY`로 바뀐 첫 날만 `buySignal == true`이고 `buySignal == true`인 점은 정확히 1개
  - `오늘_이후_점은_제외`: today 날짜와 today+1 점 포함 입력 → 결과 마지막 날짜 == today−1
  - `days만큼_뒤에서_자르되_SMA는_전체로_계산`: 120개 100 → `history(.., 10)` 크기 10, 첫 점 `sma60 == 100.00`(잘린 앞부분으로 워밍업)
  - `완성_점이_없으면_예외`: 빈 리스트 → `IllegalStateException`
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.domain.model.CryptoHashRibbonsTest' 2>&1 | grep -E "FAILED|BUILD|ERROR"` → 컴파일 실패
- [ ] **Step 3: 구현** — `CryptoValuations.history`와 같은 구조: `TreeMap`으로 `date.isBefore(today)`만, 누적합 배열로 trailing 30/60개 평균(그날 포함, scale 2 `HALF_UP`), `state = sma60 == null ? null : (sma30 < sma60 ? CAPITULATION : RECOVERY)`, `buySignal = prev.state == CAPITULATION && state == RECOVERY`. 전체 계산 후 마지막 `days`개 반환. 이동평균은 날짜 간격이 아니라 점 개수 기준 — `// ponytail: 점 개수 기준 SMA, mempool 결측일이 생기면 날짜 창으로 전환` 주석
- [ ] **Step 4: 통과 확인** (Step 2 명령, BUILD SUCCESSFUL)
- [ ] **Step 5: 커밋** — `feat(stats): Hash Ribbons 30·60일 해시레이트 이동평균 계산을 추가한다`

### Task 2: CMC 어댑터 + mempool 해시레이트

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/port/output/CryptoMarketMetricsPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/port/output/HashrateHistoryPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/coinmarketcap/{CoinMarketCapConfig,CoinMarketCapProperties,CoinMarketCapAdapter}.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/mempool/MempoolBlockHeightAdapter.java` (`HashrateHistoryPort`도 구현)
- Modify: `trading-core/src/main/resources/application.yml` (`alpaca:` 블록 뒤에 `cmc:\n  api-key: ${CMC_API_KEY:} # CoinMarketCap Pro API(무료 Basic) — 비면 도미넌스·알트 시즌 섹션만 null`)
- Modify: `deploy/server/README.md` (`.env` 목록 `ALPACA_API_SECRET=` 다음에 `CMC_API_KEY=                     # 선택 — CoinMarketCap Pro 무료 키, 비우면 코인 지표의 BTC 도미넌스·알트 시즌만 null`)
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/adapter/out/coinmarketcap/CoinMarketCapAdapterTest.java`, `.../mempool/MempoolBlockHeightAdapterTest.java`

**Interfaces:**
- Produces:
  - `CryptoMarketMetricsPort`: `BigDecimal btcDominance()`, `List<DatedValue> btcDominanceDaily(int count)`, `BigDecimal altcoinSeasonIndex()`, `List<DatedValue> altcoinSeasonDaily(LocalDate from, LocalDate to)` — 이력은 UTC 날짜 오래된 순, 같은 날짜 여러 점이면 마지막 점
  - `HashrateHistoryPort`: `List<DatedValue> dailyHashratesEh()` — 최근 2년 일별 해시레이트(EH/s, scale 2), 오래된 순
  - `CoinMarketCapProperties`: `@ConfigurationProperties(prefix = "cmc") public record CoinMarketCapProperties(String apiKey)`
  - 빈 이름: `@Configuration("statsCoinMarketCapConfig")`, `@Bean RestClient statsCoinMarketCapRestClient()` = `RestClients.withTimeouts(3_000, 7_000)`; Config에 `@EnableConfigurationProperties(CoinMarketCapProperties.class)` (`AlpacaConfig` 패턴)

- [ ] **Step 1: 실패 테스트 작성** (`MempoolBlockHeightAdapterTest` 패턴, `new CoinMarketCapAdapter(builder.build(), new CoinMarketCapProperties("test-key"))`)
  - `도미넌스_현재값은_latest의_btc_dominance`: `https://pro-api.coinmarketcap.com/v1/global-metrics/quotes/latest`, 헤더 `X-CMC_PRO_API_KEY: test-key` 검증, 응답 `{"status":{"error_code":0},"data":{"btc_dominance":60.147128048832}}` → `60.147128048832`
  - `도미넌스_이력은_UTC_날짜별`: `.../v1/global-metrics/quotes/historical?interval=daily&count=365`, 응답 `data.quotes` 2개(`"timestamp":"2026-10-15T00:00:00.000Z","btc_dominance":59.8558`, `2026-10-16...60.1`) → `[DatedValue(2026-10-15, 59.8558), DatedValue(2026-10-16, 60.1)]`
  - `알트_시즌_현재값`: `.../v1/altcoin-season-index/latest` → `{"status":{"error_code":0},"data":{"altcoin_index":57}}` → `57`
  - `알트_시즌_이력은_비공식_API를_키_없이_호출하고_날짜별_마지막_점`: `https://api.coinmarketcap.com/data-api/v3/altcoin-season/chart?start=1729468800&end=1760227200`(from=2024-10-21, to=2025-10-11 → end는 다음날 2025-10-12 UTC 자정), 헤더 `X-CMC_PRO_API_KEY` **없음** 검증(`headerDoesNotExist`), 응답 points `{"altcoinIndex":"34","timestamp":"1729468800"}`, `{"altcoinIndex":"36","timestamp":"1729490000"}`(같은 날), `{"altcoinIndex":"32","timestamp":"1729555200"}` → `[(2024-10-21, 36), (2024-10-22, 32)]`
  - `status_error_code가_0이_아니면_error_message로_실패`: `{"status":{"error_code":1008,"error_message":"You've exceeded your API Key's HTTP request rate limit."}}` → `IllegalStateException` 메시지에 `rate limit` 포함
  - `키가_비면_HTTP_호출_없이_실패`: `new CoinMarketCapProperties("")`·`null` 모두 `btcDominance()` → `IllegalStateException`, `server.verify()`(기대 0건) 통과
  - `해시레이트는_EH_s로_변환`(mempool): `https://mempool.space/api/v1/mining/hashrate/2y` → `{"hashrates":[{"timestamp":1761004800,"avgHashrate":1.085536445332513e21}]}` → `[DatedValue(2025-10-21, 1085.54)]`
  - `해시레이트_빈_응답이면_실패`: `{"hashrates":[]}` → `IllegalStateException`
- [ ] **Step 2: 실행해 실패 확인** — `--tests 'com.kista.tradingcore.stats.adapter.out.coinmarketcap.*' --tests 'com.kista.tradingcore.stats.adapter.out.mempool.*'`
- [ ] **Step 3: 구현** — 응답은 package-private 중첩 record + `@JsonProperty`(`CoinbaseMarketDataAdapter` 패턴). 공식 호출 공통 헬퍼가 키 확인 → `X-CMC_PRO_API_KEY` 헤더 → `status.error_code != 0`이면 `error_message`로 예외 → `data` null이면 예외. 비공식 응답의 `altcoinIndex`/`timestamp`는 문자열이라 `new BigDecimal`/`Long.parseLong`. 비공식 `end` = `to.plusDays(1)` UTC 자정 epoch초. 해시레이트는 `BigDecimal.valueOf(avgHashrate).movePointLeft(18).setScale(2, HALF_UP)`
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(stats): CoinMarketCap 도미넌스·알트 시즌, mempool 해시레이트 어댑터를 추가한다` (application.yml·deploy README 포함)

### Task 3: 도메인 섹션 + 서비스 조립·캐시

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoMarketIndicators.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoMarketIndicatorService.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/CryptoMarketIndicatorServiceTest.java` (+ 생성자 변경으로 깨지는 기존 테스트·`CryptoMarketIndicatorControllerTest`의 도메인 생성 코드는 새 인자 `null`로 컴파일만 맞춤)

**Interfaces:**
- Consumes: Task 1 `CryptoHashRibbons.history`, `DatedHashRibbon`; Task 2 `CryptoMarketMetricsPort`, `HashrateHistoryPort`
- Produces (Task 4가 매핑):
  - `CryptoMarketIndicators(Instant asOf, Premium usdtPremium, List<TickerIndicators> tickers, Halving halving, DailyIndicator btcDominance, DailyIndicator altcoinSeason, HashRibbons hashRibbons)` — 새 3필드 nullable
  - `public record DailyIndicator(BigDecimal current, List<DatedValue> history)` (도미넌스·알트 시즌 공용)
  - `public record HashRibbons(DatedHashRibbon current, List<DatedHashRibbon> history)` + `static HashRibbons of(List<DatedHashRibbon> history)` — 빈 이력 예외, current = 마지막 점 (`Valuation.of` 패턴)
  - `withDays`가 세 history도 `tail`, `isEmpty`가 세 섹션 null까지 포함
  - 서비스 생성자 두 개 모두 끝에 `CryptoMarketMetricsPort cmc, HashrateHistoryPort hashrates` 추가(테스트용 생성자는 `Clock` 앞)

- [ ] **Step 1: 실패 테스트 작성** — `stubAllOk()`에 `cmc.btcDominance()`=60.1, `cmc.btcDominanceDaily(365)`=어제까지 400일 + 오늘 점 1개(값 59), `cmc.altcoinSeasonIndex()`=57, `cmc.altcoinSeasonDaily(any(), any())`=어제까지 400일 + 오늘 점, `hashrates.dailyHashratesEh()`=어제까지 730일 1000 추가; `stubAllFail()`에 세 포트 예외 추가
  - `시장_전체_지표_세_섹션을_채운다`: `get(365)` → `btcDominance.current == 60.1`, history 크기 365, 마지막 날짜 == TODAY−1(오늘 점 제외); `altcoinSeason.current == 57` 동일 조건; `hashRibbons.current.date == TODAY−1`, `state == RECOVERY`, history 크기 365, 첫 점 `sma60 != null`
  - `days를_적용한다`: `get(30)` → 세 history 크기 30
  - `CMC_latest_실패면_도미넌스_알트시즌만_null`: `btcDominance()`·`altcoinSeasonIndex()` throw → 두 섹션 null, `hashRibbons`·`halving`·`tickers[0].fundingRate` non-null, `verify(cmc, never()).altcoinSeasonDaily(any(), any())`
  - `알트_이력_실패면_현재값만_유지`: `altcoinSeasonDaily` throw → `altcoinSeason.current == 57`, `history` 빈 리스트
  - `해시레이트_실패면_hashRibbons만_null`
  - `이력은_UTC_자정까지_캐시하고_실패는_캐시하지_않는다`: 첫 `get` 후 `now[0] = NOW.plus(11분)`로 두 번째 `get` → `btcDominanceDaily`·`altcoinSeasonDaily`·`dailyHashratesEh` 각 `times(1)`, `btcDominance()` `times(2)`; 별도로 첫 조회에서 `dailyHashratesEh` throw → 11분 뒤 재호출(`times(2)`)
  - `세_섹션만_살아있어도_빈_결과가_아니다`: `stubAllFail()` 후 `hashrates`만 정상 → `hashRibbons` non-null, 캐시 키 정상(재조회 없음 — 기존 `코인베이스_프리미엄만_살아있어도_빈_결과가_아니다` 패턴)
  - 기존 `stubAllFail` 기반 전부 실패 테스트가 계속 통과(세 포트도 실패)
- [ ] **Step 2: 실행해 실패 확인** — `--tests 'com.kista.tradingcore.stats.application.service.CryptoMarketIndicatorServiceTest'`
- [ ] **Step 3: 구현**
  - 상수: `DOMINANCE_HISTORY_CACHE_KEY = CACHE_KEY + ":btc-dominance-history"`, `ALTCOIN_SEASON_HISTORY_CACHE_KEY = CACHE_KEY + ":altcoin-season-history"`, `HASHRATES_CACHE_KEY = CACHE_KEY + ":hashrates"`
  - 기존 `dailyCloses`의 "다음 UTC 자정까지" 계산을 `private <T> T untilUtcMidnight(String key, Instant now, Supplier<T> loader)`로 추출해 `dailyCloses`와 세 이력이 공유
  - `assemble()` 끝(`halving` 뒤):
    - `btcDominance = guard("BTC 도미넌스", () -> new DailyIndicator(cmc.btcDominance(), beforeToday(untilUtcMidnight(DOMINANCE..., now, () -> cmc.btcDominanceDaily(MAX_DAYS + 1)), today)))` — 오늘 점이 섞여도 365개 남게 `MAX_DAYS + 1` 요청
    - `altcoinSeason`: `BigDecimal alt = guard("알트 시즌 지수", cmc::altcoinSeasonIndex)`; null이면 섹션 null; 아니면 이력을 별도 try/catch(warn `"알트 시즌 이력 조회 실패 — 현재값만"`)로 `untilUtcMidnight(ALTCOIN..., () -> cmc.altcoinSeasonDaily(today.minusDays(MAX_DAYS), today))` → `beforeToday`, 실패 시 `List.of()`
    - `hashRibbons = guard("Hash Ribbons", () -> HashRibbons.of(CryptoHashRibbons.history(untilUtcMidnight(HASHRATES..., now, hashrates::dailyHashratesEh), today, MAX_DAYS)))`
    - `beforeToday(List<DatedValue>, LocalDate)`: `date.isBefore(today)` 필터 private static 헬퍼
  - 클래스 상단 주석에 세 이력의 자정 캐시 언급 추가
- [ ] **Step 4: 통과 확인** (서비스 테스트 + `--tests 'com.kista.tradingcore.stats.*'`로 생성자 변경 컴파일 영향 확인)
- [ ] **Step 5: 커밋** — `feat(stats): 코인 시장 지표에 BTC 도미넌스·알트 시즌·Hash Ribbons 섹션을 조립한다`

### Task 4: 응답 DTO·OpenAPI·문서

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/dto/CryptoMarketIndicatorsResponse.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/openapi/CryptoMarketIndicatorsOpenApiCustomizer.java` (`"CryptoMarketIndicatorsResponse"` 목록에 `btcDominance`, `altcoinSeason`, `hashRibbons` 추가)
- Modify: `docs/agents/modules/tradingcore/stats.md` (11행 포트 목록에 `CryptoMarketMetricsPort`·`HashrateHistoryPort`, 14행 서비스 설명에 세 섹션·자정 캐시·알트 이력 예외, 15행 어댑터 목록에 `coinmarketcap/` + mempool 해시레이트)
- Modify: `src/test/resources/architecture/flows.yml` (49행 title에 `도미넌스·알트 시즌·Hash Ribbons` 추가, 804~808행 desc에 CMC·mempool 해시레이트 소스, fail에 "CMC 키 없음·실패 시 도미넌스·알트 시즌 null, 비공식 알트 이력 실패 시 현재값만, 이력 3종 다음 UTC 자정까지 캐시")
- Modify: `README.md` — 외부 연동 목록이 있으면 CoinMarketCap 추가(없으면 변경 없음)
- Test: `.../adapter/in/web/CryptoMarketIndicatorControllerTest.java`, `.../adapter/in/web/openapi/CryptoMarketIndicatorsOpenApiCustomizerTest.java`

**Interfaces:**
- Consumes: Task 3 `CryptoMarketIndicators.btcDominance()/altcoinSeason()/hashRibbons()`, `DailyIndicator`, `HashRibbons`, `DatedHashRibbon`
- Produces: 스키마 `CryptoMarketIndicatorsDailyIndicator{current, history: CryptoMarketIndicatorsDatedValue[]}`, `CryptoMarketIndicatorsHashRibbons{current, history}`, `CryptoMarketIndicatorsDatedHashRibbon{date, hashrateEh, sma30|null, sma60|null, state: "CAPITULATION"|"RECOVERY"|null, buySignal}`

- [ ] **Step 1: 실패 테스트 작성**
  - customizer 테스트 `실패시_null_섹션은_ref와_null_oneOf로_생성한다`에 `btcDominance`/`altcoinSeason` → `CryptoMarketIndicatorsDailyIndicator`, `hashRibbons` → `CryptoMarketIndicatorsHashRibbons` 추가; `null_가능_스칼라는_null_타입을_포함한다`에 `CryptoMarketIndicatorsDatedHashRibbon`의 `sma30`·`sma60`·`state` 추가
  - 컨트롤러 테스트 null 직렬화 케이스에 `$.btcDominance`·`$.altcoinSeason`·`$.hashRibbons` nullValue, 채움 케이스에 `$.btcDominance.current`=60.1, `$.altcoinSeason.history[0].value`=55, `$.hashRibbons.current.state`="CAPITULATION", `$.hashRibbons.current.buySignal`=false, `$.hashRibbons.current.hashrateEh`=1050.12
- [ ] **Step 2: 실행해 실패 확인** — `--tests 'com.kista.tradingcore.stats.adapter.in.web.*'`
- [ ] **Step 3: 구현** — 최상위 필드 3개(`@Schema(description = "... (실패 시 null)", nullable = true, requiredMode = REQUIRED)`) + 중첩 record `DailyIndicatorResponse`(`@Schema(name = "CryptoMarketIndicatorsDailyIndicator")`, history는 기존 `DatedValueResponse` 재사용)·`HashRibbonsResponse`·`DatedHashRibbonResponse`(state는 enum 그대로, nullable 스칼라에 `nullable = true`). 설명: 도미넌스 "BTC 시가총액 비중 (%)", 알트 시즌 "0~100 (CMC — 상위 100 알트 중 90일간 BTC를 이긴 비율, 75↑ 알트 시즌·25↓ 비트코인 시즌). 이력은 비공식 API라 실패 시 빈 배열", Hash Ribbons "해시레이트(EH/s) 30·60일 이동평균 — CAPITULATION: sma30 < sma60(채굴자 항복), buySignal: 항복→회복 전환일"
- [ ] **Step 4: 통과 확인** + 아키텍처 맵 검증 `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`
- [ ] **Step 5: 전체 스위트 1회** — `./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"` (postgres 기동 필요: `docker compose up -d postgres`)
- [ ] **Step 6: 커밋** — `feat(stats): 시장 전체 지표 응답·OpenAPI 계약과 문서를 갱신한다`
