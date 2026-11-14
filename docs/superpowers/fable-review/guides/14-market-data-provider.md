# 지침서 14. 공용 시장 데이터 공급자 전환 — 토스 관리자 키 제거

> **실행 세션은 먼저 맨 끝의 11절 "실행 전 보정"과 `00-roadmap.md` 9절(공통 규칙)을 읽는다.** 본문과 다르면 그쪽이 우선한다. (2026-11-14)

작성 2026-11-14 (Fable). 근거: `findings/19-market-data-consumers.md`(소비처·호출 빈도·배치 시세 흐름), `findings/21-market-data-providers.md`(Twelve Data·Alpaca 문서 대조와 실호출, 8절은 Fable 직접 교차 조회), `findings/14-alpaca-market-data.md`. 직접 읽은 코드: `TradingPriceFetcher`(199줄 전체), `TradingService`의 마감(105~165행)·접수(260~280행)·개장(335~430행) 구간.

**이 지침서는 지침서 04의 4절(4-1·4-2·4-3)과 단계 F를 대체한다.** 04의 2-2절(시세 포트 분리)은 이 지침서의 T1·T3으로 흡수한다.

## 0. 사용자 결정

확정(2026-11-13 밤):

- 관리자 증권사 키로 받는 공용 시장 데이터를 없앤다. 이유는 토스 키의 1년 갱신 부담이다.
- 토스는 다른 증권사처럼 주문 브로커로만 남긴다.
- 공급자는 Twelve Data와 Alpaca 중에서 고른다.

**확정(2026-11-14, 사용자)**: D1 Alpaca, D2·D3 권고대로. D4·D5는 "권고대로"와 함께 온 추가 지침으로 고쳤다 — **주문 경로의 현재가는 Alpaca가 아니라 그 계좌의 증권사 시세 API로 읽는다**(3-5절). Alpaca는 화면(대시보드·차트·원장 합산·미리보기)과 확정된 값(전일 종가·확정 종가·일봉)에 쓴다. 아래 표는 처음 낸 권고이고, D4·D5 행은 3-5절이 대체한다.

| # | 내용 | 권고 |
|---|---|---|
| D1 | 공급자 | **Alpaca.** 2절 |
| D2 | 약관. 두 공급자 모두 무료 등급의 화면 표시가 문구상 걸린다(Twelve Data는 "internal non-display usage only", 외부 표시는 월 $149부터. Alpaca는 "cannot redistribute") | Alpaca로 가되 위험을 알고 받아들인다. 벤치마크 지수는 지금도 Alpaca 값을 화면에 보여 주고 있어 새로운 종류의 위험은 아니다. 비인증 공개 엔드포인트 `GET /api/market/candles`를 로그인 필요로 바꿀지는 fable-ui 확인 뒤 정한다(`cross-repo.md` G절) |
| D3 | 확정 종가의 출처가 바뀐다. 운영 원장 종가 68건(2026-10-17~11-12)을 Alpaca 통합 일봉과 대조하면 SOXL·USD·QLD 49건은 전부 일치하고 **TQQQ는 19거래일 중 11일이 0.01~0.02달러 다르다**(`findings/21` 9절. 11-12 종가는 Alpaca와 Twelve Data가 서로 일치). 마감 리포트 시각은 마감+10분에서 **마감+20분**으로 늦춘다(무료 등급은 최근 15분 통합 시세를 주지 않는다) | 진행. 실계좌 체결은 증권사 체결 조회에서 오므로 종가는 평가금액·INFINITE 리버스 판정·VR 롤오버·Mock 체결 판정에만 쓰인다. 사용자가 말한 "근사치면 된다"의 범위 안이다 |
| D4 | ~~접수 직전 현재가가 "증권사 실시간"에서 "IEX 실시간과 15분 지연 통합 시세 중 더 최근 체결"로 바뀐다~~ | **개정**: 주문 경로의 현재가는 계좌 증권사 실시간 값이다(3-5절). 처음 권고의 근거 "BUY 가격 상한에만 쓰인다"는 틀렸다 — DCA BUY 기준가와 VR 기준가에도 쓰인다 |
| D5 | ~~시세 출처를 하나로 줄이고 계좌 증권사 시세 폴백(메리츠 시세 API)을 지운다~~ | **개정**: 폴백 체인은 그대로 지운다. 메리츠·토스의 현재가 조회는 폴백이 아니라 주문 경로의 1차 출처로 남는다. 값마다 출처는 하나다(3-5절) |

사용자 결정은 전부 받았다. T3(전환)의 남은 조건은 T2 통과뿐이다.

## 1. 현재 구조와 문제

- 시세 경로가 3단이다: 관리자 소스(토스 → KIS) → 계좌 어댑터 → 종목별 단건 재시도. `BrokerPricePort`의 메서드가 8개(현재가·전일 종가·확정 종가 × 묶음·단건 등)이고 `BrokerRouter`(`broker/application/service/BrokerRouter.java:81-122`)가 관리자 우선으로 돌린다. `TradingPriceFetcher`(199줄)의 절반이 묶음 실패 시 단건 폴백 골격과 "토스 계좌 우선 선택"(`selectPriceAccount`)이다. 시세가 계좌와 무관한 값인데 모든 호출이 계좌를 인자로 받는다.
- 토스 관리자 전용 코드가 약 700줄이다(`TossPriceApi` 255, `TossCandleApi`·`TossCandle` 195, `TossAdminPriceSource` 59, `UsdKrwRateCache`와 환율 부분 약 80, 인증·`getCommon` 부분 약 60. `findings/19` 6절). `TossPriceApi:163-199`에는 DST 시각표 복제가 있다.
- 캐시가 제각각이다. 현재가는 캐시가 없고(사용자 요청 경로 3곳이 요청마다 외부 호출), 전일 종가는 만료 없는 JVM 맵에 실패까지 저장하고, 일봉 600봉은 2분, 환율은 60초다.
- `MockBrokerAdapter`가 포트가 아니라 `TossPriceApi`를 직접 주입받는다(`MockBrokerAdapter.java:29`).
- 관리자 키는 사람이 1년마다 갱신해야 하고, gitignore된 `application-local.yml:40-41`에 실값이 적혀 있다.

하루 호출량은 문제가 아니다. 운영 종목 4개(SOXL·TQQQ·USD·QLD) 기준 배치가 하루 약 22콜이다(`findings/19` 2-2절).

## 2. 공급자 판정: Alpaca

같은 분에 6종목을 두 공급자로 조회한 결과(`findings/21` 8절)가 판정의 핵심이다.

| 항목 | Twelve Data 무료 | Alpaca 무료 |
|---|---|---|
| 실시간 현재가 | IEX 한 거래소 규모(통합 거래량의 1~2%). SOXL 값이 Alpaca `iex`와 같은 숫자였다 | 같다(`feed=iex`) |
| 거래가 적은 종목 | MAGX가 통합 시세와 3.7% 어긋남. 대체 수단 없음 | `feed=delayed_sip`(15분 지연 통합 시세)이 무료 키로 동작한다. MAGX도 정상 |
| 전일 종가 | 6종목 전부 통합 종가와 일치 | 통합 일봉(`feed=sip`) 6종목 전부 일치. `iex` 일봉은 5종목 불일치 |
| 일봉 이력·조정 | 가능 | 가능(1회 10,000봉, 분할·배당 조정). 백테스트가 이미 쓴다 |
| 호출 한도 | 분당 8·하루 800 크레딧, **묶음도 종목당 1크레딧** | 분당 200회, 묶음 1회에 종목 여러 개 |
| 휴장일·조기 마감 | 유료(무료 키 403) | 가능. KISTA가 이미 쓴다 |
| 환율 USD/KRW | 가능 | 불가 |
| 기술 지표 | 가능(RSI 1크레딧) | 없음 |
| 약관(화면 표시) | Basic은 "internal non-display usage only" | "cannot redistribute" |
| 키 | 새 벤더, 새 키 | 이미 운영 중인 키(`ALPACA_API_KEY`). 만료 정책은 둘 다 문서에 없다 |
| 코드 | 어댑터·설정 신규 | 어댑터 3개와 설정이 이미 있다 |

Alpaca를 권고하는 이유:

1. 현재가 품질이 같은데 Alpaca만 거래가 적은 종목의 대안(지연 통합 시세)이 있다.
2. Twelve Data의 장점 두 가지(환율, 지표)는 KISTA에 필요가 없다. 환율 소비처는 벤치마크 비교 응답 하나뿐이고 하루 1회 참고 환율이면 된다(Frankfurter 어댑터가 이미 코드에 있다). 지표는 3-2절.
3. 휴장일 때문에 Alpaca는 어차피 남는다. Twelve Data를 고르면 공급자가 둘이 된다.
4. 분당 8 크레딧은 설계를 한도에 맞추게 만든다(3-1절의 저장 테이블·시간 배치·호출 간 대기). 분당 200회에서는 필요 없다.

**Twelve Data를 고를 경우 달라지는 것**: 4절의 포트는 같고 어댑터만 다르다. 추가로 ① 시세 저장 테이블과 1시간 배치(`findings/19` 2-3절, 하루 약 220크레딧) ② 분당 8을 넘지 않게 호출을 나누는 대기(마감 접수 구간이 약 13크레딧) ③ UPRO·QLD·USD·MAGX의 장중 현재가를 쓰지 않는 규칙(전일 종가로 대체)이 필요하다. 휴장일은 Alpaca에 그대로 둔다.

## 3. 사용자 질문에 대한 답

### 3-1. "1시간에 1번 배치로 받아 두고 개장·마감 배치만 실시간" — Alpaca에서는 만들지 않는다

이 구상은 Twelve Data의 크레딧 한도를 피하려는 것이다. Alpaca는 종목 전체를 1회 호출로 받고 분당 200회까지 되므로 저장 테이블과 시간 배치가 필요 없다. "실시간일 필요가 없다"는 취지는 캐시 시간으로 반영한다(4-2절): 현재가 60초, 일봉 1시간, 확정된 값(전일 종가·확정 종가)은 그날 내내.

### 3-2. 인디케이터는 쓰지 않는다

- KISTA가 계산하는 지표는 SMA와 RSI(14, Wilder)뿐이고 `DcaIndicators` 43줄이다. 입력인 조정 일봉 250봉은 평단 조건과 SMA(최대 200) 때문에 어차피 받는다. 지표 API를 쓰면 호출이 줄지 않고 늘어난다.
- 공급자 지표는 계산식(Wilder 평활 여부, 조정 종가 여부)이 문서에 없고, 실호출에서 진행 중인 당일 봉을 포함한 값을 줬다. KISTA는 확정 봉만 쓴다(`DcaPlanInputCollector`). 백테스트는 과거 임의 시점의 지표가 필요해 자체 계산을 지울 수도 없다.
- 운영에서 RSI·SMA 조건을 쓰는 것은 삭제될 바이낸스 전략뿐이다(주식 DCA는 `AVG_PRICE` 조건만 5건, `findings/20` 4-2절).
- 주식 공포탐욕지수는 두 공급자 모두 주지 않는다. CNN 수집은 그대로 둔다.

### 3-3. 개장 배치 시작의 `loadPriceContext` — 지금은 필요하고, 지침서 06 뒤에는 조회가 한 번으로 합쳐진다

지금 개장 배치는 22:00에 시세를 읽고, 30분(DST)이나 90분을 잔 뒤, 그 묵은 값으로 계획(대상 선별·예산 배정·INFINITE 0회차 진입가·VR 기준가)을 세우고, 접수 직전에 현재가만 다시 읽는다(`TradingService.java:346` → `:359` 대기 → `:418`).

- `reloadPlacementPrices`만으로는 안 된다. 계획 단계가 **전일 종가**를 쓰는데(INFINITE 0회차 진입 방향과 진입가, DCA 참조가) 재조회는 현재가만 읽는다.
- 그러나 22:00에 읽을 이유는 없다. 22:00 조회가 필요했던 것은 ① KIS DCA 개장 전 접수(KIS 삭제와 04 단계 B로 사라진다) ② 잠들기 전에 계획을 세워 두던 구조 때문이다. 전일 종가는 22:00이든 개장 시각이든 같은 값이다.
- 지침서 06의 T3은 개장 단계를 `openAt`에 예약한다(잠이 없다). 그러면 조회 → 계획 → 접수가 수 초 안에 끝나므로 **조회를 한 번만 한다**: 개장 시각에 현재가와 전일 종가를 함께 읽고 `reloadPlacementPrices`를 지운다. 지금보다 계획 단계의 가격이 30~90분 더 신선해진다.

### 3-4. 마감 배치의 현재가 체이닝 — 지운다

마감 배치의 시세 조회에는 세 겹의 "한 번 더"가 있다. 셋 다 없앤다.

| 겹 | 지금 | 판정 |
|---|---|---|
| 접수 직전 현재가 재조회(`reloadPlacementPrices`) | 자동 실행에서는 마감 30분 전에 발화해 대기가 0에 가깝다. 첫 조회 몇 초 뒤에 같은 값을 다시 읽는다. 의미가 있는 것은 수동 실행이 주문 시각까지 기다릴 때뿐이다 | 06 T2(마감 접수 단계에 잠이 없다) 뒤에 지운다. 단계 하나에 조회 하나 |
| 출처 체인(관리자 토스 → 관리자 KIS → 계좌 어댑터 → 종목별 단건) | 출처가 여럿이라 생긴 구조다. 폴백이 운영에서 실제로 일어났다는 근거는 없다(`findings/19` 5-3절) | 값마다 출처 하나(현재가는 계좌 증권사, 종가는 Alpaca. 3-5절), 어댑터 안에서 1회 재시도. 실패하면 대체값 없이 실패 |
| 확정 종가가 없으면 현재가로 대체(토스·Mock 계좌 어댑터, `TossPriceApi.java:202-206`) | 일봉이 아직 안 나온 경우를 라이브 가격으로 메운다. 틀린 종가가 원장에 남는다 | 지운다. 확정 봉이 없으면 리포트 단계가 실패하고 다시 돈다 |

체인을 지워도 되는 근거는 06의 단계 모델이다(현재가는 3-5절처럼 계좌 증권사에서 읽지만 다른 출처로 넘어가는 폴백은 없다). 세 단계 모두 창이 있고(`OPEN_PLACE`는 개장~마감 30분 전, `CLOSE_PLACE`는 마감 31분 전~10분 전, `CLOSE_REPORT`는 그 거래일 KST 자정까지) 매시 `reconcile`이 끝나지 않은 단계를 다시 실행한다. 출처를 여러 개 두는 대신 같은 출처에 다시 묻는다. 06 이전 구조에서는 어댑터의 1회 재시도가 그 역할을 하고, 그래도 실패하면 지금 체인이 전부 실패했을 때와 같은 동작(종목 제외 + 관리자 알림)이 된다.

### 3-5. 주문할 때의 현재가는 그 계좌의 증권사에서 읽는다 (2026-11-14 사용자 지침, 채택)

사용자 질문: Alpaca는 대시보드에 쓰고, 매매 배치가 주문할 때는 전략마다 그 사용자의 증권사 현재가 API를 부르면 어떤가. 사용자가 100명, 1,000명이어도 비용이 크지 않을 것 같다.

**채택한다.** 근거(코드 확인 2026-11-14):

1. **현재가는 상한 계산에만 쓰이지 않는다.** BUY 가격 상한(`BuyOrderPriceCapper:44-45,79`) 말고도 주식 DCA BUY 기준가가 max(전일 종가, 현재가)이고(`DcaCycleOrderStrategy.referencePriceForBuy`), VR 사다리·bootstrap의 기준가가 현재가다(`CycleOrderComputer.java:101-102`). 주문 가격을 정하는 값이다. INFINITE만 계획에 전일 종가를 쓰고 현재가는 상한에만 쓴다.
2. **Alpaca 무료 현재가는 주문 가격의 기준으로 약하다.** `iex`는 통합 거래량의 1~2%이고 통합 시세는 15분 늦다. 개장 직후 15분은 하루 중 가장 많이 움직이는 구간이다. 새로 넣는 EETH는 2026-11-13 `iex` 일봉 거래량이 100주(통합 56,160주)이고 `iex` 종가 23.13 대 통합 23.33이었다. 증권사 값은 주문을 받는 바로 그곳의 실시간 값이다.
3. **비용이 작다.** 호출 수는 Σ(계좌별 서로 다른 종목 수)다. 메리츠는 종목당 1회(`MeritzPriceApi`, 다건 조회 없음), 토스는 계좌당 1회(다건 200개)다. 지금 운영은 배치 단계당 6회 안팎이고, 계좌 1,000개에 종목 2개씩이어도 2,000회다. 한도(메리츠 10 TPS, 앱키별 110ms 간격)는 사용자 키마다 따로 걸리고 계좌 사이는 지금도 병렬이다(`TradingService.java:269`). 계좌마다 이미 잔고 조회와 주문 접수를 하고 있어 새 종류의 부하가 아니다. 확인하지 못한 것: 메리츠가 출발 IP 단위로도 제한하는지.
4. **장애가 계좌 단위로 갇힌다.** 한 계좌의 시세 조회가 실패하면 그 계좌만 빠진다. 증권사 시세가 죽었으면 주문도 어차피 못 넣는다. Alpaca가 죽어도 현재가는 영향이 없다.

값별 출처(값 하나에 출처 하나, 다른 출처로 넘어가는 폴백 없음):

| 값 | 출처 | 쓰는 곳 |
|---|---|---|
| 주문 경로의 현재가 | 계좌 증권사(계좌 토큰). Mock 계좌는 증권사가 없으므로 Alpaca | 개장·마감 배치의 계획과 접수, 수동 실행(`ManualTradingService`), VR 재설정(`VrReconfigureService`) |
| 전일 종가 | Alpaca | 배치 계획(INFINITE 0회차, DCA 기준가·조건), 미리보기, 이력 |
| 확정 종가·일봉 | Alpaca | 마감 리포트, Mock 체결 판정, 차트, DCA 지표, 백테스트 |
| 화면의 현재가 | Alpaca(60초 캐시) | 원장 합산(`AccountLedgerTotalsService`), 미리보기, Mock 잔고, 대시보드 |

전일 종가를 증권사에서 읽지 않는 이유: 확정된 값이라 모든 사용자에게 같아야 하고(INFINITE 0회차 진입가가 계좌마다 달라지면 안 된다), 토스 현재가 응답에는 전일 종가가 없어 캔들 호출이 따로 필요하고, 하루 캐시라 개장 몇 시간 전부터 읽어 둘 수 있다. 메리츠 응답의 `prdy_clpr`는 쓰지 않는다.

배치가 바뀌는 모양:

- `loadPriceContext`: 전일 종가는 Alpaca에서 종목 전체를 1회, 현재가는 계좌별로 그 계좌 전략의 종목만 병렬 조회한다. 가격 맵의 키가 종목에서 (계좌, 종목)으로 바뀐다. `selectPriceAccount`(시세용 계좌 하나 고르기)는 사라진다.
- 실패 처리는 지금과 같되 범위가 계좌다: 그 계좌의 그 종목 전략만 빠지고, 관리자 알림은 배치당 1건으로 묶는다("현재가 조회 실패: 계좌 N개 …").
- `reloadPlacementPrices`도 T7 전까지는 같은 계좌별 조회를 쓴다.

지금과 달라지는 것: 지금도 메리츠 계좌의 현재가는 결국 증권사 실시간 값이다(관리자 토스가 1차, 계좌 어댑터가 2차). 1차가 "관리자 토스"에서 "자기 계좌 증권사"로 바뀔 뿐이라 주문 가격의 성격은 그대로다. 그래서 처음의 D4(현재가 품질 변화)는 주문 경로에서 사라진다.

기각: 증권사 현재가 실패 시 Alpaca로 넘어가기(체인이 다시 생기고, 증권사가 죽었으면 주문도 실패한다), 계좌 증권사에서 전일 종가까지 읽기(위 이유).

## 4. 목표 구조

### 4-1. 포트 하나

```java
// broker.application.port.output — 계좌 인자가 없다. 지침서 05 단계 R에서 market 모듈로 옮긴다
public interface MarketDataPort {
    Map<StrategyTicker, BigDecimal> latestPrices(Collection<StrategyTicker> tickers);                 // 근사 현재가
    Map<StrategyTicker, DailyBar> confirmedBars(Collection<StrategyTicker> tickers, LocalDate sessionDate); // 그 세션의 확정 일봉(OHLC). 없으면 키 없음
    Map<StrategyTicker, BigDecimal> closesBefore(Collection<StrategyTicker> tickers, LocalDate sessionDate); // 그 세션 직전 확정 종가
    List<Candle> dailyCandles(StrategyTicker ticker, CandleQuery query);                              // 차트·DCA 지표·백테스트
}
```

- `AdminMarketPricePort`, `AdminPriceSource`, `CandlePort`, stats의 `HistoricalCandlePort`가 이 포트 하나로 합쳐진다.
- `BrokerPricePort`는 메서드 8개에서 **1개**로 줄어 남는다(3-5절): `Map<StrategyTicker, BigDecimal> currentPrices(BrokerAccountRef account, Collection<StrategyTicker> tickers)`. `BrokerRouter`는 관리자 우선 로직 없이 계좌의 브로커로만 보낸다. 메리츠는 `MeritzPriceApi`의 현재가 조회(종목별 반복), 토스는 `GET /api/v1/prices`를 **계좌 토큰**으로, Mock은 `MarketDataPort.latestPrices`에 위임한다. 주문 경로만 이 포트를 쓰고 화면 경로는 `MarketDataPort`를 쓴다. 시그니처의 세부(반환 타입 이름, `CandleQuery`의 모양)는 구현자가 기존 `Candle`·`PriceSnapshot` 타입을 재사용해 정한다.
- 구현은 `broker/adapter/out/marketdata/`의 Alpaca 어댑터 하나다(`AdminMarketPriceAdapter`가 있는 자리). stats의 `AlpacaCandleAdapter`(78줄)를 이쪽으로 합친다.
- 인터페이스를 두는 이유: 실제 외부 구현이 있고(지침서 01의 유지 조건), 테스트 대역과 Mock 브로커가 주입받는다.
- 환율은 별도다. `ExchangeRatePort.getExchangeRate()`의 구현을 Frankfurter로 바꾼다(4-3절).

### 4-2. 값별 호출과 캐시

| 값 | Alpaca 호출 | 캐시 |
|---|---|---|
| 현재가 | `GET /v2/stocks/snapshots?symbols=…`를 `feed=iex`와 `feed=delayed_sip`으로 한 번씩. 종목별로 `latestTrade.t`가 더 늦은 쪽의 `latestTrade.p`. 한쪽이 실패하면 다른 쪽만으로 답한다 | 종목별 60초(`TtlResultCache`) |
| 전일 종가 | `GET /v2/stocks/bars?timeframe=1Day&feed=sip&adjustment=split&start=<세션일−10일>`(**`end` 없음**)에서 날짜가 세션일보다 앞인 마지막 봉의 종가 | (종목, 세션일) 키로 그날 내내. 실패는 저장하지 않는다 |
| 확정 일봉 | 같은 엔드포인트, `start`=세션일, **`end` 없음**, `adjustment=raw`에서 날짜가 세션일인 봉. **마감+16분 이후에만 완결된다** | (종목, 세션일) 키로 그날 내내. 빈 결과는 저장하지 않는다 |
| 차트 일봉 | 같은 엔드포인트 1회(최대 10,000봉), 조정 방식은 지금 토스 호출과 같게 | 종목별 1시간 |
| DCA 지표 일봉 250봉 | 같은 엔드포인트, `adjustment=all`, 세션일 이전까지 | 호출 측의 세션당 1회 캐시(`DcaPlanInputCollector.closeCache`)를 그대로 쓴다 |
| 환율 | Frankfurter 최신 환율 | 하루 |
| 휴장일 | 변경 없음 | — |

- `snapshot`의 `dailyBar`·`prevDailyBar`는 쓰지 않는다. 지연 피드에서 날짜 경계의 의미가 문서에 없다. 종가는 항상 날짜를 지정한 일봉 조회로 얻는다.
- **`feed=sip` 일봉은 최근 세션을 읽을 때 `end`를 넣지 않는다.** 무료 키는 `end`가 최근 15분에 걸리면 403(`subscription does not permit querying recent SIP data`)이고, 날짜만 적은 `end`는 그날 끝으로 해석되는 것으로 보인다. 2026-11-14 00:21 UTC 실호출: `end=2026-11-13` 403, `start=end=2026-11-13` 403, `end` 생략 200(11-13 봉 포함), `end=2026-11-12` 200. `end`를 빼고 받은 뒤 봉의 날짜로 고른다. 과거 구간(차트·백테스트)은 `end`를 어제 이전으로 자르면 된다(`AlpacaCandleAdapter`의 `clampedTo`). `feed=delayed_sip`은 snapshot·latest 전용이고 bars에서는 400이다.
- 위 표의 "현재가"는 화면 경로와 Mock 계좌용이다. 실계좌 주문 경로는 `BrokerPricePort.currentPrices`다(3-5절).
- 전일 종가 캐시의 "실패도 저장"(`TossPriceApi.java:149-151`)은 옮기지 않는다.

### 4-3. 그 밖에 바뀌는 것

- **마감 리포트 시각**: 마감+10분 → 마감+20분. 06 판정표의 `CLOSE_REPORT` 창 시작 상수와 지금 코드의 `waitUntilPostClose` 기준을 함께 바꾼다. 조기 마감일도 같은 규칙이다(세션의 `closeAt` 기준).
- **`TradingPriceFetcher`**: 계좌 선택(`selectPriceAccount`), `PriceContext.priceAccount`, 묶음→단건 폴백 골격(`fetchWithFallback`)이 사라진다. 남는 것은 "계좌별 현재가(증권사)와 종목별 전일 종가(Alpaca)를 읽어 (계좌, 종목) 키의 `PriceSnapshot` 맵을 만들고 실패를 알림 1건으로 묶는다"와 PRIVACY 기준 매매표 조회다(약 90줄). 전일 종가가 없을 때 현재가로 대체하고 알리는 지금 동작은 그대로 둔다.
- **Mock 브로커**: `TossPriceApi` 직접 주입을 `MarketDataPort` 주입으로 바꾼다. 체결 판정은 `confirmedBars`의 OHLC를 쓰고, 확정 봉이 없을 때 현재가 단일값 봉으로 대체하던 분기(`MockBrokerAdapter.java:140-142`)는 지운다.
- **사용자 요청 경로**: 주문을 내는 것(수동 실행, VR 재설정)은 `BrokerPricePort.currentPrices`로 자기 계좌 증권사에서 읽는다. 보여 주기만 하는 것(원장 합산 `AccountLedgerTotalsService.java:56`, 미리보기, Mock 잔고)은 `MarketDataPort`로 바꾼다. 60초 캐시가 요청마다 나가던 외부 호출을 막는다.
- **토스 계좌 잔고의 원화 환산**(`TossHoldingsApi.java:64,91`): 관리자 토큰으로 부르던 토스 환율을 공용 환율 포트로 바꾼다(Fable 판단. 참고 환율이 되지만 운영에 토스 계좌 전략이 없다).
- **벤치마크**: 환율 출처 라벨(`BenchmarkService.java:240`의 `"TOSS_INVEST"`)을 공급자에서 받는다. 하루 1회 갱신되는 참고 환율이라는 것을 응답의 날짜 필드로 드러낸다(`cross-repo.md` G절).
- **벤치마크 지수 일봉**(`AlpacaIndexPriceAdapter`, `feed=iex`): `iex` 일봉 종가는 통합 종가와 다르다(6종목 중 5종목). `feed=sip`으로 바꾼다. `iex`를 고른 이유가 코드·커밋에 있으면 그 이유를 먼저 확인한다(T6).

### 4-4. 지우는 것

| 대상 | 줄(약) |
|---|---|
| `TossCandleApi`, `TossCandle`, `TossAdminPriceSource`, `UsdKrwRateCache`, `TossHoldingsApi`의 환율 부분, `TossPriceApi`에서 현재가 다건 조회를 뺀 전부(전일 종가·확정 종가·세션 판정 복제·실패 캐시) | 540 |
| `TossAuthApi`·`TossDistributedTokenCoordinator`의 관리자 부분, `TossHttpClient.getCommon`과 그 429 재시도, 설정 `toss.admin-client-id/secret` | 60 |
| `TossBrokerAdapter`의 시세·캔들·환율 위임(현재가 위임 1개는 남는다) | 50 |
| `AdminMarketPriceAdapter`, `AdminPriceSource`, `AdminMarketPricePort`, `BrokerPricePort`의 메서드 7개, `CandlePort`, `BrokerRouter`의 관리자 우선 경로 | 150 |
| `MeritzPriceApi`에서 현재가 조회를 뺀 것(전일 종가·확정 종가·일봉)과 그 위임 | 90 |
| `TradingPriceFetcher`의 폴백 골격과 계좌 선택 | 130 |
| stats `AlpacaCandleAdapter`·`HistoricalCandlePort`(합침) | 90 |

KIS 관리자 소스(`KisAdminPriceSource`·`KisAdminTokenCoordinator` 187줄)와 `KisPriceApi`는 04 단계 A가 지운다. 토스 주문 브로커가 쓰는 것(`TossHttpClient` 본체, 계좌 토큰 발급, 분산 토큰 골격, Redis 토큰 저장소, `TossOrderApi`, `TossHoldingsApi`의 나머지)은 남긴다.

## 5. 기각한 대안

- **Twelve Data**: 2절.
- **토스 유지**(2026-11-12 결정): 사용자가 뒤집었다. 키 갱신 부담이 이유다.
- **메리츠 관리자 키**: 앱키가 계좌에 묶인다(04의 4절). 그대로 기각.
- **출처 둘을 폴백으로 잇기(Alpaca 실패 시 메리츠, 또는 그 반대)**: 지금 체인의 축소판이다. 폴백이 일어난 근거가 없고 06의 단계 재실행이 같은 장애를 덮는다. 3-5절은 폴백이 아니라 역할 분담이다(주문 경로의 현재가만 계좌 증권사).
- **시세 저장 테이블 + 1시간 배치**: 3-1절.
- **확정 종가만 증권사 값 유지**(04의 4-1절 판단): 실계좌 체결은 증권사 체결 조회에서 온다. 종가 차이가 영향을 주는 곳은 평가·판정·Mock이고 차이는 TQQQ에서 0.02달러 이내다. 관리자 키를 지우려면 이 값도 옮겨야 한다.
- **주문 경로의 현재가까지 Alpaca로**(이 지침서의 첫 권고): 3-5절에서 뒤집었다.
- **현재가를 `iex` 한 피드로만**: MAGX의 `iex` 최근 체결이 이틀 전 것이었다. 지연 통합 시세와 비교해 더 최근 것을 고르는 한 줄로 막는다.

## 6. 예상 감량과 위험

- main 약 −1,100줄, +250줄(Alpaca 어댑터, 환율 최신값). 순 약 −850줄(3-5절로 증권사 현재가 조회가 남아 첫 추정보다 150줄 덜 준다). test는 토스 시세·캔들·관리자 소스·폴백 골격 테스트가 함께 빠진다(측정하지 않았다. T5에서 센다).
- 위험과 대응:

| 위험 | 대응 |
|---|---|
| `feed=delayed_sip`은 공식 문서에 무료 허용이 적혀 있지 않다(포럼 답변과 실호출로만 확인) | 한쪽 피드가 실패해도 다른 쪽으로 답하게 만든다(4-2절). 막히면 거래 적은 종목의 현재가가 묵는다 — 관리자 알림으로 드러나게 "최근 체결이 N분보다 오래됨" 경고를 남긴다 |
| 마감+20분에 당일 통합 일봉이 완결되지 않을 수 있다(마감 직후 실측을 하지 못했다) | T2의 병행 비교로 확인한다. 안 되면 리포트 시각을 더 늦춘다 |
| TQQQ 확정 종가 0.01~0.02달러 차이 | D3. VR 롤오버의 V′ 계산과 Mock 체결 판정이 그만큼 달라진다. 전환일 전후로 원장을 다시 계산하지 않는다 |
| Alpaca 장애 | 주문 경로에서는 전일 종가만 걸린다(하루 캐시라 개장 전에 읽어 둔 값이 있으면 영향 없음). 단계 재실행(06). 06 이전에는 어댑터 1회 재시도 + 알림 |
| 토스 시세를 계좌 토큰으로 부를 수 있는지 실호출로 확인하지 못했다(스펙은 "계좌 정보 없이 액세스 토큰만으로 호출") | T1에서 토스 계좌로 1회 호출해 확인한다. 안 되면 토스 계좌는 Mock처럼 `MarketDataPort.latestPrices`를 쓴다(운영에 토스 전략 0) |
| 계좌 수가 늘면 메리츠가 출발 IP 단위로 제한할 수 있다(미확인) | 계좌별 실패는 그 계좌만 빠지고 알림에 계좌 수가 찍힌다. 실제로 걸리면 계좌 병렬 수를 제한한다 |
| 약관 | D2 |
| 분할이 있는 날의 전일 종가 | `adjustment=split`. 토스 값과의 차이는 병행 비교 기간에 분할이 없으면 확인되지 않는다 — 분할 공시가 있는 주에는 전환하지 않는다 |

## 7. 동작 보존용 선행 테스트

1. **어댑터 계약 테스트**(`MockRestServiceServer`): snapshot 두 피드에서 더 늦은 체결을 고르는지, 한쪽 실패 시 다른 쪽으로 답하는지, 일봉 빈 결과를 캐시하지 않는지, 전일 종가가 "세션일 직전 봉"인지(휴장일을 사이에 둔 경우, 응답에 세션일 봉이 섞여 온 경우 포함), 전일 종가·확정 일봉 요청에 `end` 인자가 없는지. 응답 JSON은 `findings/21` 8절의 실호출 형태로 만든다.
2. **가격 컨텍스트 테스트**: `TradingPriceFetcherTest`의 시나리오(현재가 실패 종목 제외, 전일 종가 실패 시 현재가 대체, 알림 1건으로 묶기)를 새 포트 기준으로 옮긴다. 추가: 같은 종목을 가진 두 계좌 중 한 계좌의 현재가만 실패하면 그 계좌의 전략만 빠지는지, 계좌마다 자기 종목만 조회하는지. 폴백 단계 테스트(관리자 → 계좌 → 단건)는 대상이 사라지므로 지운다.
3. **마감 리포트**: 확정 봉이 없으면 리포트가 완료로 기록되지 않고 다음 실행에서 다시 도는지(06 T2 뒤에는 단계 멱등 IT가 이것을 덮는다).
4. 변이 확인: "더 늦은 체결 고르기"를 `iex` 고정으로 바꿔 실패하는지, 전일 종가의 `end`를 세션일로 바꿔 실패하는지.

## 8. 태스크

공통 완료 판정: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' --tests 'com.kista.tradingcore.trading.*' 2>&1 | grep -E "FAILED|BUILD"`, 아키텍처 테스트(root·trading-core). 배포는 매매 시간대 밖(KST 07:00~16:00). 실계좌 `*IT`는 돌리지 않는다.

| # | 내용 | 모델 | 선행 | 배포·관찰 |
|---|---|---|---|---|
| T1 | `MarketDataPort`와 Alpaca 어댑터(4-1·4-2), 7절 1번 테스트. 기존 경로는 건드리지 않는다. `AlpacaProperties`는 trading-core에 이미 있는 것을 쓴다. 토스 `GET /api/v1/prices`를 계좌 토큰으로 1회 호출해 되는지 확인하고 결과를 `questions.md`에 적는다(읽기 전용) | Opus 설계, Sonnet 구현 | 없음 | 없음 |
| T2 | 병행 비교: 배치가 토스 값으로 동작하는 그대로, 같은 시점의 새 출처 값을 조회해 `[시세 비교] ticker= kind= old= new= diffPct=` 로그를 남긴다(전일 종가와 마감+10분·+20분의 확정 종가는 Alpaca, 개장·마감의 현재가는 메리츠 계좌 하나의 증권사 값). 비교 호출의 실패는 배치에 영향을 주지 않는다(`BrokerCallGuard.tryGet`) | Sonnet | T1 | 3거래일. 통과 기준: 전일 종가 전부 일치, 현재가(관리자 토스 대 계좌 증권사) 차이 0.5% 이내, 확정 종가는 SOXL·USD·QLD 일치·TQQQ 0.02달러 이내, 마감+20분에 확정 봉이 조회됨. 결과를 `questions.md`에 표로 적는다 |
| T3 | 전환: 주문 경로의 현재가를 계좌별 `BrokerPricePort.currentPrices`로(3-5절), 나머지 소비처를 `MarketDataPort`로(4-3절), 마감 리포트 +20분, `TradingPriceFetcher` 축소, Mock 포트 주입, 7절 2·3번 테스트. T2의 비교 로그 코드 삭제 | Opus | T2 통과 | 2거래일. 접수 주문의 가격·수량, 리포트 시각과 종가, 알림을 전환 전날과 대조 |
| T4 | 환율: `ExchangeRatePort` 구현을 Frankfurter 최신값으로(기존 `FrankfurterFxRateAdapter`의 설정 재사용, 하루 캐시, 날짜 함께 반환), 벤치마크 라벨, 토스 잔고 환산. `cross-repo.md` G절 | Sonnet | 없음(T3과 독립) | 벤치마크 비교 화면 1회 확인 |
| T5 | 삭제(4-4절): 토스 관리자 코드·설정, 관리자 소스 체인, 메리츠·토스 시세에서 현재가를 뺀 나머지, 옛 포트 메서드. `.env.example`·`application*.yml`·`deploy/server/README.md`의 `TOSS_ADMIN_*`. 문서: `toss-api.md`, `workflow-ledger-price.md`의 "시세 출처", `flows.yml`, `README.md`, `modules/tradingcore/broker.md`·`stats.md`. 운영 secret에서 뺄 키를 `cross-repo.md`에 적는다 | Sonnet(전수표), Opus(검토) | T3 관찰 종료 | 1거래일 |
| T6 | 벤치마크 지수 일봉을 `feed=sip`으로. 이미 저장된 지수 값과 비교해 차이가 있으면 다시 받을지 `questions.md`로 올린다 | Sonnet | 없음 | 없음 |
| T7 | `reloadPlacementPrices` 삭제, 개장 단계의 시세 조회를 한 번으로(3-3·3-4절) | Opus | **06 T2·T3** | 06의 관찰에 포함 |

사용자 작업: T5 배포 뒤 토스 관리자 키를 토스 개발자 콘솔에서 폐기하고 로컬 `application-local.yml`의 값을 지운다. 대화로 전달한 Twelve Data 키는 쓰지 않게 되면 폐기한다.

## 9. 판단이 갈릴 때의 기본값, 중단 조건, 하지 말 것

기본값:

- 포트 메서드 이름·반환 타입은 기존 타입을 재사용하는 쪽으로 정한다. 새 값 타입은 확정 일봉(OHLC)에 맞는 기존 타입이 없을 때만 만든다.
- 차트 일봉의 조정 방식은 지금 토스 호출과 같게 한다(`TossCandleApi`의 호출 인자를 보고 정한다). 화면 숫자가 달라지면 `cross-repo.md`에 적는다.
- 캐시는 `TtlResultCache`를 쓴다. Redis로 옮기지 않는다(시세를 읽는 프로세스는 하나다).

중단 조건(`questions.md`에 적고 그 태스크를 멈춘다):

- T2의 통과 기준 중 하나라도 어긋난다.
- `feed=delayed_sip`이 403·400을 낸다.
- 주문·원장 계산이 환율을 쓰는 곳이 발견된다(`grep -rn "ExchangeRate\|exchangeRate" trading-core/src/main`).
- 확정 종가가 null일 때 원장 저장이 어떻게 되는지가 코드에서 불명확하다(`CyclePositionPersistor.saveCyclePosition`).

하지 말 것:

- T2 없이 주문 경로의 가격 출처를 바꾸지 않는다.
- 사용자 계좌 키로 **다른 사용자나 화면에 쓰일** 시세를 부르지 않는다. 계좌 키로 읽은 현재가는 그 계좌의 주문에만 쓴다(계좌 사이 캐시 공유 금지).
- 증권사 현재가가 실패했을 때 Alpaca 값으로 주문 가격을 정하지 않는다.
- 확정 봉이 없을 때 현재가로 대체하는 분기를 새 어댑터에 다시 만들지 않는다.
- 토스 주문 브로커의 토큰·주문 코드를 이 작업에서 고치지 않는다(04 단계 C·D의 몫).
- 시세 저장 테이블, 시간 배치, Twelve Data 어댑터를 만들지 않는다(D1이 Twelve Data로 나면 2절 끝 문단을 태스크로 풀어 이 지침서를 고친다).
- T3과 06의 T2~T5를 같은 배포에 싣지 않는다.

## 10. 다른 지침서와의 관계

- **04**: 4절 전체와 단계 F를 이 지침서가 대체한다. 2-2절의 시세 포트 분리와 `MockBrokerAdapter` 포트 주입은 T1·T3이다. 단계 A(KIS 삭제)가 먼저 끝나 있으면 T5의 범위에서 KIS 관리자 소스가 빠져 있다. 순서는 어느 쪽이 먼저여도 된다.
- **06**: `CLOSE_REPORT` 창 시작이 `closeAt + 20분`이 된다. T7은 06 T2·T3 뒤다. 13에서 지적한 `TossPriceApi`의 세션 판정 복제는 T5에서 사라진다.
- **05**: 단계 R에서 `MarketDataPort`와 어댑터를 `market` 모듈로 옮긴다. 통합 뒤 root의 캔들 중계(`CandleQueryHttpAdapter` → `CandleInternalController`)는 프로세스 내 호출이 된다. Alpaca 설정 3벌 정리는 13의 작은 수정 그대로다.
- **12**: F1(Alpaca 일봉 페이지네이션 없음)은 T1의 어댑터에서 `next_page_token`을 따라가게 만들어 함께 닫는다.
- **15**: 코인 지표가 쓰던 Frankfurter 과거 환율 조회(`HistoricalFxRatePort`)는 15가 지운다. T4는 같은 설정으로 최신 환율만 남긴다. 15가 먼저 실행되면 `FrankfurterConfig`를 지우지 말라고 15에 적어 두었다.
- **cross-repo**: G절(환율 라벨·날짜, 차트 엔드포인트 인증, 운영 secret).

## 11. 실행 전 보정 (2026-11-14 — 본문과 다르면 이 절이 우선)

공통 규칙은 `00-roadmap.md` 9절. 측정 원본은 `findings/24`(단, 그 파일의 "`waitUntilPostClose` 메서드 0건"은 틀렸다. `DstInfo.waitUntilPostClose`가 있고 `TradingService.java:151,174,240`이 부른다. 4-3절의 "마감 리포트 +20분"은 이 메서드의 기준을 바꾸는 것이 맞다).

전제 정정:

- **`AlpacaProperties`는 세 벌이고 trading-core 안에만 둘이다**(marketcalendar는 `baseUrl` 포함, stats는 `dataBaseUrl` 포함. root benchmark에 하나 더). 새 어댑터가 놓일 broker 모듈은 다른 모듈의 `adapter/out`을 참조할 수 없다. T1에서 `shared.platform`에 한 벌(`apiKey`, `apiSecret`, `baseUrl`, `dataBaseUrl`)을 두고 trading-core의 두 벌을 그것으로 바꾼다. yml 키(`alpaca.*`)는 그대로다. root benchmark의 것은 05 단계 X·R에서 합친다(네 번째를 만들지 않는 것이 요점이다).
- `BuyOrderPriceCapper:44-45`는 필드 선언이다. 현재가를 쓰는 줄은 파일에서 다시 찾는다. 3-5절의 결론은 그대로다.
- `KisAdminPriceSource`·`KisAdminTokenCoordinator`·`KisPriceApi`는 04 단계 A가 지운다. 04가 아직이면 14 T5는 KIS 파일을 건드리지 않고 관리자 소스 체인에서 KIS 항목만 뺀다.

중단 조건을 좁힌다(9절):

- "주문·원장 계산이 환율을 쓰는 곳이 발견된다"의 확인 명령은 `git grep -lE "ExchangeRate|exchangeRate" -- trading-core/src/main/java/com/kista/tradingcore/trading trading-core/src/main/java/com/kista/tradingcore/matching`이다. 2026-11-14 결과는 0건이다. 본문의 명령(`trading-core/src/main` 전체)은 broker·stats의 표시용 환율(잔고 환산, 포트폴리오 요약)까지 잡아 언제나 걸린다. 그것들은 중단 사유가 아니다.
- "확정 종가가 null일 때 원장 저장이 불명확하다": `CyclePositionPersistor.saveCyclePosition`을 읽어 종가가 없으면 저장하지 않고 예외·건너뜀으로 끝나는지 본다. 그렇지 않으면(null이나 현재가로 저장) T3 안에서 "확정 봉이 없으면 리포트를 완료로 기록하지 않는다"(7절 3번 테스트)를 먼저 넣고 진행한다. 멈추지 않는다.
- "`feed=delayed_sip`이 403·400": `feed=iex` 하나로 답하게 두고(4-2절의 한쪽 실패 처리 그대로) "최근 체결이 N분보다 오래됨" 경고를 남긴다. 주문 경로의 현재가는 증권사에서 읽으므로 주문에는 영향이 없다. 멈추지 않고 사용자에게 알린다.
- "T2의 통과 기준이 어긋난다": 아래 T2 항목.

같은 커밋에서 고칠 가드:

- T5에서 `TradingPriceFetcher`의 메서드(`reloadPlacementPrices`는 T7), `AdminMarketPriceAdapter`, `BrokerPricePort#getPrevClose`를 지우거나 이름을 바꿀 때 `flows.yml`의 `code:`(`:118,172,188,315,372,531,672`)를 같은 커밋에서 고친다. T5의 문서 목록에 있는 `flows.yml`은 서술 수정이고 이것은 별개다.
- `usTradeDates_must_only_be_used_by_allowlisted_adapters`(`HexagonalArchitectureTest:112-130`): 새 Alpaca 어댑터는 `UsTradeDates`를 쓰지 않는다. 세션일은 호출하는 쪽이 인자로 준다(7절 1번 테스트의 "세션일 직전 봉"). 그래서 허용 목록에 더할 것이 없다. `TossPriceApi`를 지우는 T5에서 목록과 주석(`:116`)·`DisplayName`의 개수를 고친다.
- Alpaca 응답 모델은 어댑터 패키지 안의 package-private record로 둔다. 포트는 기존 도메인 타입을 돌려준다(9절 기본값). `broker.domain.model.alpaca` 패키지를 만들지 않는다.

태스크별 기본값:

- **T1의 토스 실호출**: 운영에 토스 전략이 없고 실행 세션은 실계좌를 부르지 않는다. 로컬 `application-local.yml`에 토스 계좌 키가 있고 사용자가 허락하면 로컬에서 1회 부른다. 아니면 하지 않고, 토스 계좌는 6절의 대체 경로(Mock처럼 `MarketDataPort.latestPrices`)로 만든다. 나중에 토스 전략이 생기면 그때 확인한다는 한 줄을 `toss-api.md`에 남긴다.
- **T2의 "메리츠 계좌 하나"**: 운영에서 활성 전략이 있는 메리츠 계좌 중 생성이 가장 이른 것. 비교 호출은 그 계좌의 토큰으로 그 계좌가 가진 종목만 부른다.
- **T2의 판정**: 통과 기준이 숫자라 실행 세션이 판정한다. 3거래일의 비교 로그를 표로 만들어 `questions.md`에 "자체 결정"으로 적고 T3으로 간다. 어긋나면 어긋난 값의 종류로 가른다: 전일 종가·확정 종가가 어긋나면 T3을 하지 않고 표를 사용자에게 보인다(D3에서 받아들이기로 한 TQQQ 0.02달러를 넘는 차이다). 현재가만 0.5%를 넘으면 조회 시각 차이인지 로그의 시각으로 확인하고, 시각 차이로 설명되면 통과다. 마감+20분에 확정 봉이 없으면 +30분으로 한 번 더 재고 그 값으로 4-3절의 상수를 정한다.
- **T3의 배포**: 주문 가격의 출처가 바뀐다. 배포 전 확인표(로드맵 9-4)에 T2의 표와 "분할 공시가 있는 주가 아님"을 넣는다.
- **T4**: 기존 `FrankfurterFxRateAdapter`가 과거 날짜용이면 같은 설정·`RestClient`로 최신값 메서드를 더한다. 어댑터를 새로 만들지 않는다. 15가 `HistoricalFxRatePort`를 지워도 `FrankfurterConfig`는 남긴다.
- **T6**: 저장된 지수 값과 `feed=sip` 값이 다르면 다시 받지 않는다. 차이의 크기(최대·평균)를 커밋 메시지에 적고 새로 받는 날부터 `sip`을 쓴다. 과거 값을 고치는 것은 사용자가 요청할 때 한다.
- **T5 뒤의 사용자 작업**: 토스 관리자 키 폐기, 운영 secret에서 `TOSS_ADMIN_*` 제거(kista-infra, `cross-repo.md`에 적는다).
