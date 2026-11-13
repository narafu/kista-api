# 21. 외부 시세 공급자: Twelve Data 무료 vs Alpaca 무료

조사일 2026-11-13(KST 11-14 00:5x). 읽기 전용 조사. Twelve Data는 실호출 포함(키는 `<KEY>`로 마스킹), Alpaca는 `14-alpaca-market-data.md`를 전제로 보강만 했다. 결론은 내리지 않고 사실만 적는다. "미확인"은 문서·실호출로 확인하지 못한 것.

대상 종목(`StrategyTicker`, 코인 제외): MAGX, USD(ProShares Semiconductors 2X), TQQQ, SOXL, UPRO, QLD. 실호출은 SOXL·TQQQ만 했다. MAGX(신규 상장 ETF)·USD(통화 약어와 같은 심볼)·UPRO·QLD의 Twelve Data 지원은 **미확인**.

## 1. Twelve Data 무료(Basic) 한도

| 항목 | 내용 | 출처 |
|---|---|---|
| 한도 | 분당 8 크레딧, 일 800 크레딧. 분당은 매 분 리셋, Basic만 일일 한도가 있고 UTC 자정 리셋 | https://twelvedata.com/pricing.md , https://support.twelvedata.com/en/articles/5615854-credits |
| 엔드포인트 비용(문서) | `/price` `/quote` `/eod` `/time_series` `/exchange_rate` `/currency_conversion` 심볼당 1, `/rsi` 심볼당 1, `/market_state` `/earliest_timestamp` `/api_usage` 요청당 1 | https://twelvedata.com/llms-full.txt (공식 문서 텍스트본) |
| 멀티 심볼 | 심볼 수만큼 차감. 지원센터 공식: "Data Weight x Number of Symbols = Credits Used". `POST /batch`는 "credits = sum of parts" | 위 지원센터 글, llms-full.txt |
| 분당 초과 | HTTP 429 + JSON `code 429`. 지원센터: "a 429 error code will notify you" | 실호출(아래 5절) |
| 사용량 조회 | `GET /api_usage` — 실호출로 **이 호출도 1 크레딧을 소모**함을 확인(5절). 문서와 일치 | 실호출 |

## 2. Twelve Data 무료 플랜 데이터 범위

- **실시간/지연**: 가격 페이지 표 `Basic | $0 | 8 (800/day) | US | Real-time US equities, forex, crypto`. 지연 시간·데이터 출처(거래소/SIP 여부)는 문서에 **명시 없음**(llms-full.txt도 출처·지연 미기재). 실호출 관찰은 5절.
- **프리·애프터마켓(`prepost`)**: llms-full.txt 기준 `/time_series`·`/quote`의 `prepost`는 **Pro 이상**, `/price`·`/eod`는 플랜 표기 없음. 실호출: 무료 키로 `/price?prepost=true`가 200 정상 응답했으나 장중이라 프리·애프터 반영 여부는 **미확인**.
- **`/quote` 필드**: 문서는 `previous_close`·`is_market_open`을 정의하지 않는다. 관찰: `previous_close`=직전 정규장 종가로 보이는 값(TQQQ 59.88), `close`=현재 진행 값, `is_market_open` true(장중). 장 마감 후 확정 종가가 언제 반영되는지는 **미확인**(장 마감 시각에 호출하지 못함).
- **일봉 이력**: `/earliest_timestamp?symbol=SOXL&interval=1day` → `2010-03-11`(SOXL 상장 시점). `/time_series` `outputsize` 상한과 무료 한도 사이 관계는 **미확인**. `adjust` 옵션은 문서상 `all/splits/dividends/none`, 문서는 기본값을 적지 않으며 "corporate action adjustments are built in"이라 함. 실호출로 무료 키에서 `adjust=all`이 오류 없이 동작.
- **환율**: `/exchange_rate?symbol=USD/KRW` 무료 키로 동작(5절). 갱신 주기 공식 문서 **미확인**(응답 timestamp가 분 단위로 보임).
- **휴장일·조기 마감**: `/market_state?exchange=NYSE`는 무료 키로 동작하나 현재 개장 여부·남은 시간만 줌(과거·미래 일정 아님). `/exchange_schedule`은 실호출 시 HTTP 403 `is available exclusively with ultra or enterprise plans`.

## 3. 이용 조건 (Twelve Data)

| 항목 | 내용 | 출처 |
|---|---|---|
| Basic | 가격 페이지 기능 목록: "internal non-display usage only", 개인 플랜은 "individuals only — personal/internal/non-commercial" | https://twelvedata.com/pricing.md , https://twelvedata.com/pricing |
| 개인 플랜 재배포 | "Individual plans are intended strictly for personal or internal use"; 개인 플랜은 제3자 재배포·상업적 화면 표시 불가 | https://support.twelvedata.com/en/articles/5332349-commercial-and-personal-usage |
| 외부 화면 표시 최소 플랜 | **Venture(Business)** "external display data access" — 가격 페이지 표 $149 / $299 / $499 월. 구조화 데이터(JSON-LD)에는 다른 금액(Grow $29, Pro $99 등)이 있어 **가격 표기가 페이지 안에서도 불일치**함. 재배포는 별도 계약 필요 | pricing.md, 위 지원센터 글 |
| 캐싱·저장 | Venture에서 "temporary caching" 허용, 파생 데이터는 원본 복원 불가 조건으로 저장 가능 | 위 지원센터 글 |
| 약관 원문 | https://twelvedata.com/terms 는 2020-10-24 판만 검색됨. 최신 약관 원문의 구체 문구는 읽지 못함 | **미확인** |

즉 문서 문구상 **Basic은 비표시(non-display) 내부 용도**이고, 사용자에게 시세를 화면으로 보여 주는 서비스는 Business(Venture 이상)에 해당한다고 읽힌다. 법적 해석은 하지 않으며 Twelve Data에 서면 확인이 필요하다.

## 4. 기술 지표 (Twelve Data)

- `/rsi`는 문서상 1 크레딧(심볼당)이고 실호출도 1 크레딧(5절). 기본 인자는 `series_type: close`, `time_period: 14`(응답 meta에 표시).
- 지표 계산식(Wilder 평활 여부)·조정 종가 사용 여부는 문서에서 **미확인**. 응답 `values[].rsi`로 직접 계산값과 비교하는 검증이 필요.
- `time_series`와 지표를 한 호출로 합치는 방법: 이번 조사에서 단일 호출 방법은 확인하지 못함(`POST /batch`가 있으나 크레딧은 부분의 합). **미확인**.
- 주식 시장 공포탐욕지수 같은 심리 지표: llms-full.txt·가격 페이지에서 제공 언급을 찾지 못함 → **제공 확인 못 함**.
- 직접 계산 대비: Twelve Data 지표 호출은 지표당 1 크레딧이라 하루 800에 비해 소량이지만, 계산식 불일치 위험은 위 미확인 항목과 같다. (일봉을 이미 받는 경우 직접 계산은 추가 호출 0.)

## 5. 실호출 결과 (Twelve Data)

호출 시각 2026-11-13 15:52~15:54 UTC = 뉴욕 10:52~10:54 EST = **미국 정규장 중**(개장 09:30 후 약 1시간 23분, `/market_state`의 `time_after_open 01:23:21`와 일치). 총 소모 약 16 크레딧(401 응답은 무소모).

| 호출 | 결과 | 크레딧 |
|---|---|---|
| `/price?symbol=SOXL` | `{"price":"54.83"}` | 헤더 `api-credits-request: 1` |
| `/quote?symbol=TQQQ` | `datetime 2026-11-13`, `open 59.9`, `high 59.94`, `low 55.69`, `close 56.37`, `volume 1015566`, `previous_close 59.88`, `average_volume 90622257`, `is_market_open true`, `last_quote_at 1794585060`(=15:51:00 UTC) | 1 |
| 배치 `/price?symbol=SOXL,TQQQ` | `{"SOXL":{"price":"54.83"},"TQQQ":{"price":"56.6"}}` (심볼 키 객체 구조) | `api-credits-request: 2` — **심볼당 1**, `/api_usage` 전후 비교도 일치(4→7, 배치 직전 포함) |
| `/exchange_rate?symbol=USD/KRW` | `{"symbol":"USD/KRW","rate":1337.47259,"timestamp":1794585060}` | 1 |
| `/rsi?symbol=SOXL&interval=1day&outputsize=2` | `rsi 36.24016`(2026-11-13, 진행 중인 당일 포함), `39.93710`(11-12), meta: `mic_code ARCX, type ETF` | 1 |
| `/time_series?symbol=SOXL&interval=1day&outputsize=5` | 2026-11-13 `open 63.33 high 64.7 low 52.97 close 54.83 volume 1400441`(진행 봉), 11-12 `close 65.53 volume 176264200` 등 | 1 |
| `/time_series ... adjust=all outputsize=1` | 무료 키로 정상 | 1 |
| `/price?prepost=true` | `54.69` 정상 응답(무료 키) | 1 |
| `/market_state?exchange=NYSE` | XNYS·XASE·ARCX 각각 `is_market_open true`, `time_to_close 05:06:39` | 1 |
| `/earliest_timestamp?symbol=SOXL&interval=1day` | `2010-03-11` | 1 |
| `/exchange_schedule?exchange=NYSE` | HTTP 403, `/exchange_schedule is available exclusively with ultra or enterprise plans` | 무소모 |
| 분당 초과(9번째) | HTTP 429, `{"code":429,"message":"You have run out of API credits for the current minute. 9 API credits were used, with the current limit being 8. ..."}` | 헤더 `api-credits-left: 0` |
| 키 오류 | HTTP 401, `code 401, "**apikey** parameter is incorrect or not specified..."` | 무소모 |
| `/api_usage` 응답 | `{"current_usage":1,"plan_limit":8,"daily_usage":1,"plan_daily_limit":800,"plan_category":"basic"}` | 1 |

관찰된 것(해석은 보수적으로):
- 응답 헤더에 `api-credits-used`(분 창 누적), `api-credits-left`(분 창 잔여), `api-credits-request`(해당 요청 비용)가 있다. `api-credits-left`는 일일 잔여가 아니라 **분당 잔여**였다.
- **거래량이 장중 진행 대비 매우 작다.** 뉴욕 10:53 시점 TQQQ `volume 1,015,566`(평균 거래량 90,622,257), SOXL 당일 봉 `volume 1,400,441`(전일 176,264,200). 개장 후 83분이면 평균의 상당 비율이 거래됐어야 하므로 **무료 데이터가 단일 거래소/표본 체결 기반일 가능성**이 있으나, 문서가 출처를 밝히지 않아 **확인 못 함**(Alpaca IEX가 전체의 약 2.5%라는 문서와 비슷한 규모이긴 하다는 점만 사실로 적는다).
- 같은 시각 `/price` 56.6과 `/quote.close` 56.37이 달랐다(`last_quote_at`이 분 단위 15:51:00). 갱신 지연은 최소 1~2분 수준으로 보이나 정확한 지연은 측정하지 못함.
- 장외 시 `/price`가 무엇을 주는지는 **미확인**(장외 시간에 호출하지 못함). 다음 장외(KST 06:00 이후) 재측정 필요.
- 한 번에 최대 호출 가능 수: 분당 8이라 6종목 현재가를 한 번에 받으려면 요청 1회(6 크레딧)가 분 한도의 대부분을 쓴다.

## 6. Alpaca 보강 (14번 문서에 없던 것만)

| 항목 | 내용 | 출처/상태 |
|---|---|---|
| 분당 한도 | 무료 historical API **200/min**(문서). Broker 표에서도 동일 수치 | https://docs.alpaca.markets/docs/about-market-data-api |
| `feed=delayed_sip` 무료 사용 | 공식 문서에는 무료 등급 허용 명시 없음(Basic은 "equities only the IEX exchange"). Alpaca 포럼 답변은 "without Algo Trader Plus ... use feed='delayed_sip'"(15분 지연 SIP)이라 했고 사용자가 동작 확인 → **포럼 근거, 비공식**. snapshot·latest에서도 되는지는 **미확인**(실호출 안 함) | 포럼(검색 결과 요약), https://docs.alpaca.markets/us/reference/stocksnapshotsingle |
| IEX vs SIP 가격 차이 | 공식 FAQ 예시(AAPL 2023-09-29: IEX 종가 171.29, SIP 171.21 — 14번 문서에 이미 있음). 레버리지 ETF에 대한 별도 공개 비교는 못 찾음 | 14번 문서, 포럼 스레드 "Alcapa Current quote price is way off from market price"(비공식, 미정독) |
| 일봉 종가가 공식 종가인지 | FAQ: official close(`M`) 체결은 일봉을 갱신하지 않는다(14번에 이미 있음). 실측 미수행 → **미확인** | 14번 문서 |
| 프리·애프터 포함 | bars·latest의 시간외 반영은 문서에서 확인 못 함(분봉은 반영, 일봉 OHLC는 반영 안 됨 — 14번). 오버나잇은 유료 | 14번 문서 |
| 환율·기술지표 | 환율은 `/v1beta1/forex/...` 베타, KRW 미확인(14번). **기술 지표 엔드포인트는 문서에서 찾지 못함**(지표는 직접 계산 전제) | 14번 문서, 이번 조사 |
| 휴장일 캘린더 | KISTA가 이미 `GET /v2/calendar`(조기 마감 `close` 반영) 사용 중. 신형 `/v3/calendar/{market}`도 있음(14번) | 14번 문서, 코드 |
| 이용 조건(재배포·상업) | Alpaca 지원 페이지: "you cannot redistribute Alpaca API data." 예외 문구 없음. 거래소 약관(NASDAQ·NYSE) 재표시 요건은 비공식 자료(2차)만 확인, 공식 약관 PDF 문구는 읽지 못함 → 다수 사용자 표시 허용 여부 **확정 못 함** | https://alpaca.markets/support/redistribute-alpaca-api |
| 키·계정 만료 | Alpaca API 키 만료·휴면 정책은 문서·검색에서 **찾지 못함**(미확인). OAuth 토큰은 15분(Trading API는 OAuth 미지원이라 키 방식 유지). 포럼에 paper 계정 리셋 시 키 재발급이 필요하다는 언급 | 14번 문서, 검색 |
| Twelve Data 키 만료 | 문서·지원센터에서 **찾지 못함**(미확인). 무료 키는 이메일 가입으로 즉시 발급(401 응답 문구가 "get your free API key instantly") | 실호출 401 메시지 |

## 7. 비교표 (사실만, 결론 없음)

범례: 된다 / 안 된다 / 조건부 / 미확인. Alpaca 칸은 14번 문서 + 6절 기준.

| 필요 데이터 | Twelve Data 무료 | Alpaca 무료 |
|---|---|---|
| ① 현재가(정규장) | **된다**: `/price` 실호출 성공, 분 단위 갱신. 다만 출처·거래량이 표본성으로 보이는 정황(5절), 정확한 지연 미측정. 프리·애프터는 `prepost`가 `/quote`에서 Pro+, `/price`는 미표기·무료 키 응답은 왔으나 효과 미확인 | **조건부**: IEX 체결가(전체 거래량 약 2.5%), `delayed_sip`은 포럼 근거만. SIP 실시간은 유료 $99 |
| ② 전일 종가·확정 종가 | **조건부**: `/quote.previous_close` 제공, 일봉 `/time_series`·`/eod` 있음. 마감 후 몇 분에 확정되는지 **미확인** | **조건부**: 일봉 SIP bars(마감+15분 후 조회 가능), 공식 종가 일치는 **미확인**(FAQ가 official close 미반영이라 함) |
| ③ 일봉 이력(분할·배당 조정) | **된다**: SOXL 2010-03 이후, `adjust=all` 무료 키 동작. 수년치 `outputsize` 한도 **미확인** | **된다**: bars 1회 10000봉, `adjustment=split/dividend/all`, 2016년 이후 |
| ④ USD/KRW | **된다**: `/exchange_rate` 실호출 1337.47, 1 크레딧 | **미확인**(forex 베타, KRW 쌍 미확인) |
| ⑤ RSI 등 지표 | **된다**: `/rsi` 실호출 1 크레딧. 계산 기준(Wilder 등) **미확인** | **안 된다(미발견)**: 지표 엔드포인트 문서 없음, 직접 계산 |
| ⑥ 휴장일·조기 마감 | **조건부**: 무료 키는 `/market_state`(현재 개장 여부·남은 시간)만, 일정 API `exchange_schedule`은 Ultra+ (403 실측) | **된다**: `/v2/calendar`(KISTA 이미 사용, 조기 마감 반영)·`/v3/calendar`·`/v3/clock` |
| 호출 한도 | 분당 8, 일 800 크레딧(심볼당 1) | 분당 200(historical) |
| 화면 표시(재배포) | **조건부/문구상 불리**: Basic은 "internal non-display usage only", 외부 표시는 Venture($149~) | **조건부/불확실**: "cannot redistribute" 문구, 약관 원문 미확인 |
| 키 만료·갱신 | 미확인 | 미확인 |

한도 계산(참고): 1시간 1회 배치로 6종목 `/price`는 6 크레딧(분당 8 이내), 하루 24회면 144 크레딧(일 800의 18%). 일봉·지표를 종목별로 추가하면 종목당 +1~2씩 늘며 분당 8 때문에 호출을 분 단위로 나눠야 한다(배치 한 번이 6 크레딧을 쓰면 같은 분의 나머지는 2).

## 남은 미확인

- 장외 시간 `/price`·`/quote`의 값(마지막 정규 종가인지 애프터마켓인지), 마감 후 확정 종가 반영 시각 — 다음 장외·마감 직후(KST 06:05 전후) 재호출 필요(분 한도 고려해 3~4 크레딧).
- MAGX·USD·UPRO·QLD 심볼 지원 여부(각 1 크레딧).
- Twelve Data 무료 데이터의 실제 출처·지연, RSI 계산식(자체 계산 RSI와 숫자 비교).
- Twelve Data 최신 약관 원문, 키 만료 정책. Alpaca 약관·키 만료 정책.
- Alpaca `delayed_sip` snapshot 실호출, 무료 IEX와 SIP 일봉 종가 표본 비교(14번 6절).

## 8. 같은 시각 교차 조회 (Fable 직접 확인, 2026-11-13 15:55 UTC = 뉴욕 10:55, 정규장 중)

로컬 설정의 Alpaca paper 키(무료 등급)와 Twelve Data 무료 키로 6종목을 같은 분에 조회했다. Alpaca는 `GET /v2/stocks/snapshots?symbols=…&feed=…`, Twelve Data는 `/quote`.

| 종목 | Alpaca `iex` 최근 체결 (시각) | Alpaca `delayed_sip` 최근 체결 (시각) | Twelve Data `close` | 전일 종가: iex / delayed_sip / Twelve Data | 당일 거래량: iex / delayed_sip / Twelve Data |
|---|---|---|---|---|---|
| SOXL | 53.67 (15:55:36) | 53.81 (15:40:38) | 53.805 | 65.49 / **65.53** / **65.53** | 1.05M / 95.3M / 1.47M |
| TQQQ | 56.13 (15:55:27) | 56.08 (15:40:38) | 56.14 | 59.88 / 59.88 / 59.88 | 0.68M / 36.8M / 1.04M |
| UPRO | 136.43 (15:54:18) | 136.32 (15:40:26) | 136.75 | 140.28 / **140.23** / **140.23** | 10.9K / 1.15M / 23.1K |
| QLD | 77.90 (15:55:35) | 77.83 (15:40:34) | 78.07 | 81.275 / **81.30** / **81.30** | 30.9K / 2.41M / 39.3K |
| USD | 52.43 (15:55:11) | 52.46 (15:40:24) | 52.75 | 58.34 / **58.90** / **58.90** | 13.0K / 610K / 16.5K |
| MAGX | 56.185 (**11-11** 19:25) | 54.745 (15:32:06) | 56.75 | 54.29(**11-06** 봉) / **55.69** / **55.69** | 401 / 5,392 / 13 |

확인된 사실:

- **Alpaca `feed=delayed_sip`은 무료 키로 snapshot에서 동작한다**(HTTP 200). 15분 지연된 통합 시세이고 거래량이 통합 규모다. `feed=sip`은 403 `subscription does not permit querying recent SIP data`.
- **전일 종가는 `delayed_sip`과 Twelve Data가 6종목 전부 일치한다.** `iex`는 6종목 중 5종목이 다르다(USD는 0.95% 차이, MAGX는 엿새 전 봉).
- **무료 실시간 값은 두 공급자 모두 한 거래소(IEX 규모)의 체결이다.** Twelve Data SOXL `close` 53.805는 Alpaca `iex` 당일 봉 종가 53.805와 같은 숫자이고, 거래량도 통합의 1~2%다.
- **거래가 적은 종목의 무료 실시간 값은 쓸 수 없다.** MAGX: Alpaca `iex`의 최근 체결은 이틀 전 것이고, Twelve Data `close` 56.75(거래량 13)는 같은 시각 통합 시세 54.745와 3.7% 다르다. UPRO·QLD·USD도 Twelve Data가 통합 시세보다 0.3~0.6% 높았다(15분 시차 포함이라 엄밀한 비교는 아니다).
- Twelve Data는 6종목 전부 심볼을 인식한다(`USD`는 ProShares Ultra Semiconductors로 해석, 배치 4종목에 4크레딧).

남은 확인(장 마감 뒤에만 가능): 마감 직후 `delayed_sip` 당일 봉 종가와 Twelve Data `/eod`·`/quote.close`가 확정 종가로 바뀌는 시각, 토스 확정 종가와의 일치.

## 9. 운영 원장 종가와 Alpaca 일봉 대조 (Fable 직접 확인, 2026-11-14)

운영 `trading.cycle_position.closing_price`(마감 리포트가 저장한 종가, 읽기 전용 SELECT)를 종목·미국 거래일로 묶어 Alpaca `GET /v2/stocks/bars?timeframe=1Day&adjustment=raw`의 종가와 비교했다. 기간 2026-09-15~11-12, 140건(종목 4개: SOXL·TQQQ·USD·QLD).

| 구간 | `feed=sip` 일치 | 불일치 |
|---|---|---|
| 9월 하순 | 7 | 26 |
| 10월 상순 | 6 | 27 |
| 10월 하순 | 31 | 7 |
| 11월 상순 | 27 | 6 |

- 10월 중순 이전의 불일치는 값 차이가 크다(SOXL 2026-09-22: 운영 151.09, 통합 151.95). 관리자 시세 체인(토스 확정 일봉 우선, 커밋 `8a125897a`)이 들어오기 전에는 운영 종가가 확정 종가가 아니었다는 뜻이다.
- **2026-10-17 이후 68건**: SOXL 19·USD 19·QLD 11건은 전부 일치. **TQQQ는 19건 중 11건이 0.01~0.02달러 다르다**(예: 11-12 운영 59.86, Alpaca 통합 59.88, Alpaca `iex` 59.88, Twelve Data `previous_close` 59.88). 어느 쪽이 거래소 공식 종가인지는 확인하지 못했다. 다른 두 공급자가 서로 일치한다는 것만 사실이다.
- `feed=iex` 일봉 종가는 같은 140건 중 10건만 운영 값과 일치한다. 종가 출처로 쓸 수 없다.
- 같은 날 같은 종목인데 전략마다 저장된 종가가 다른 경우가 3건 있었다(전부 10월 중순 이전).

일봉 조회의 무료 등급 제약(2026-11-13 장중 실호출): `feed=sip`은 `end`를 주지 않아도 200이고 당일 진행 봉(15분 전까지)을 포함한다. `feed=delayed_sip`은 bars에서 400 `invalid feed`다(snapshot·latest 전용). 응답 헤더 `X-RateLimit-Limit: 200`.
