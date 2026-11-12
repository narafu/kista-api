# 14. 토스 관리자 키 공용 시장 데이터의 Alpaca 대체 가능성

조사일 2026-11-12. 읽기 전용 조사(코드 + Alpaca 공식 문서 WebFetch/WebSearch). 실제 API 호출·키 사용 없음. 비교 대상은 `11-broker-api-fit.md` 8절(메리츠 안). 출처 표기가 없는 Alpaca 사실은 docs.alpaca.markets 공식 페이지에서 확인한 것이고, "2차"는 비공식·검색 요약, "미확인"은 문서에서 못 찾은 것.

## 1. KISTA의 현재 Alpaca 사용 현황 (코드 확인)

Alpaca 어댑터 3벌은 모두 **Market Data·Trading API 계정 키 1쌍**(`ALPACA_API_KEY`/`ALPACA_API_SECRET`, 두 프로세스 `application.yml`에 동일 env)을 쓰고, 인증은 모두 헤더 `APCA-API-KEY-ID`/`APCA-API-SECRET-KEY` 방식이다(OAuth 아님). 구독 등급을 적은 코드·문서는 없다. 코드 주석이 근거 하나를 준다: `AlpacaIndexPriceAdapter` 주석에 "무료 플랜에서 feed=sip은 end가 최소 15분 이전이어야 한다, end=오늘이면 403 `subscription does not permit querying recent SIP data`(실측 확인)" — 즉 **운영 키는 무료(Basic) 등급**이다.

| 어댑터 (줄 수) | 프로세스 | 엔드포인트 / base URL | 파라미터 | 호출 빈도 |
|---|---|---|---|---|
| `AlpacaCalendarAdapter` 97 (+Config 20, Properties 6) — `marketcalendar` | trading-core | `GET /v2/calendar` / `alpaca.base-url` = `https://paper-api.alpaca.markets` (**paper 계정 키**, Trading API) | `start`,`end`(연 단위·월 단위 갱신), 응답 `date/open/close`(뉴욕 현지, 조기 마감 반영) | 연 1회 + 월 갱신(소량). 빈 응답·주말·중복은 예외 처리 |
| `AlpacaCandleAdapter` 78 (+Config 20, Properties 9) — `stats` 백테스트 | trading-core | `GET /v2/stocks/{symbol}/bars` / `alpaca.data-base-url` = `https://data.alpaca.markets` | `timeframe=1Day`, `adjustment=split`, **`feed=sip`**, `limit=10000`, `end`는 NY 어제로 클램프(무료 15분 제약 회피), `next_page_token` 미구현(경고만) | 사용자 백테스트 요청 시 |
| `AlpacaIndexPriceAdapter` 66 (+Config 17, Properties 6) — `benchmark` | root(api) | 같은 bars 경로 | `adjustment=split`, **`feed=iex`**(오늘 end 증분 동기화 때문에 sip 불가), `limit=10000` | `MarketIndexPriceSyncScheduler` 매일 09:00 KST 1회(지수 ETF 심볼 몇 개) |

- 합계 약 319줄(설정 클래스·Properties 3벌 중복 포함). `/v2/clock`은 쓰지 않는다. 실시간 현재가·snapshot·최신 체결은 쓰지 않는다.
- 어댑터 간 feed가 다르다(백테스트 sip, 지수 동기화 iex) → 같은 심볼의 같은 날 종가가 두 feed에서 달라질 수 있는 기존 비일관성이 이미 있다(후술 IEX vs SIP 종가 차이).
- 호출량은 하루 수십 회 이하로 무료 한도(분당 200)에 비해 매우 작다.

## 2. Alpaca 공식 문서 확인

### 2-1. `issuetokens` 페이지의 정체

| 질문 | 확인 결과 |
|---|---|
| 무엇인가 | `POST /oauth2/token`(Authentication API, 운영 `https://authx.alpaca.markets/v1`, 샌드박스 `authx.sandbox...`). `client_credentials`(client_id 20~32자 대문자·숫자 + client_secret)·JWT bearer·refresh_token 그랜트. 응답 `access_token`, `expires_in`(예시 900초), `Bearer` |
| 누가 쓰나 | 이 페이지는 Broker/Connect/Trading을 구분해 말하지 않는다. 토큰 출처로 Keycloak("brokerdash")·Google("admin")만 언급 |
| 인증 문서(`/docs/authentication`) | 두 방식: ① **Legacy 키**(`APCA-API-KEY-ID`+`APCA-API-SECRET-KEY` 헤더 또는 Basic) ② **Client credentials OAuth**(15분 유효 Bearer). OAuth는 **"Trading API에는 아직 제공되지 않음"**. Market Data API는 live·paper 모두 `data.alpaca.markets`, 키는 계정별 분리 |
| 폐지 예고 | 문서는 키 방식을 "older"라 부르지만 **폐지 계획·일정은 없다**(두 페이지 모두) |
| KISTA 필요성 | **불필요.** 시세만 받는 서버는 기존 키 헤더로 충분하고 현재 코드 그대로 동작한다. OAuth 전환은 폐지 공지가 나온 뒤에 판단하면 된다(Trading API 쪽 캘린더는 OAuth 미지원이라 어차피 키 헤더가 남는다) |

### 2-2. 등급별 제약 (공식 `about-market-data-api`)

| | Basic(무료) | Algo Trader Plus |
|---|---|---|
| 월 요금 | $0 | **$99** (문서 표. 2차 검색 요약에는 $9 표기가 있었으나 공식 표를 따름) |
| 주식 실시간 feed | **IEX 단일 거래소**(문서상 전체 거래량의 약 2.5%) | 미국 전 거래소(SIP, 100%) |
| 과거 데이터 | 2016년 이후, **최근 15분은 조회 불가** | 2016년 이후 제한 없음 |
| 과거 API 호출 한도 | **200/min** | 10,000/min |
| WebSocket 심볼 | 30 | 무제한 |
| 오버나잇(24/5) feed | 무료 포함 여부 문서에 없음. 별도 월 구독, 가격은 영업팀 문의 | 위와 같음 |

- feed 종류(`snapshots`·`latest trades` 문서): `sip`, `iex`, `delayed_sip`(15분 지연 SIP), `boats`(Blue Ocean 야간), `overnight`(BOATS 파생, 체결은 15분 지연·스프레드 내 보정), `otc`. bars 문서는 `sip/iex/otc/boats`만 나열(`delayed_sip`·`overnight` 없음). **기본 feed: 무제한 구독이면 sip, 아니면 iex.**
- FAQ: 무료는 **과거 조회에서 `end`가 15분 이상 지났으면 SIP도 가능**, **latest 계열(snapshot 포함)의 SIP는 구독 필요**. 위반 시 403 `subscription does not permit querying recent SIP data`.
- **무료 등급의 `delayed_sip`를 latest/snapshot에서 쓸 수 있는지는 문서에서 확인하지 못했다.** 쓸 수 있다면 15분 지연 SIP 현재가가 무료로 가능하다(실측 필요).

### 2-3. 엔드포인트 스펙

| 엔드포인트 | 확인 내용 |
|---|---|
| `GET /v2/stocks/snapshots?symbols=&feed=&currency=` | 다중 심볼. 응답 심볼별 `latestTrade`(p,s,x,t,c)·`latestQuote`·`minuteBar`·`dailyBar`(당일)·`prevDailyBar`(전일). 최대 심볼 수 문서 미기재. 무료 기본 feed=iex |
| `GET /v2/stocks/trades/latest?symbols=&feed=` | 다중 심볼, 응답 `trades.{sym}.{p,s,t,x,c,z}`. 홀로트(조건 I) 등은 제외 |
| `GET /v2/stocks/bars?symbols=&timeframe=&start=&end=&adjustment=&feed=&limit=&page_token=&sort=` | timeframe `1-59Min`, `1-23Hour`, `1Day`, `1Week`, `Month`. `adjustment`: `raw`(기본)·`split`·`dividend`·`spin-off`·`all`(콤마 조합). `limit` 최대 **10000(전체 데이터 포인트 합계, 심볼별 아님)**, 심볼→시간 순 정렬이라 한 페이지에 한 심볼만 담길 수 있어 `next_page_token` 반복 필요. 응답 `t,o,h,l,c,v,n,vw`. 기본 feed sip. 단일 심볼 `/v2/stocks/{symbol}/bars`는 KISTA가 이미 사용 |
| 일봉 5년·600봉 | 일봉 5년≈1,260봉 ≪ 10000이므로 **1회 호출로 충분**(단일 심볼 기준). 2016년 이후 가능 → 5년 요구 충족 |
| 일봉 집계 규칙(FAQ) | **공식 종가(official close `M`)·시가(`Q`) 체결은 bar의 OHLC·거래량을 갱신하지 않는다.** 시간외(`T`) 체결은 분봉만 갱신하고 일봉 OHLC에는 반영 안 됨. 즉 일봉 close = 마감 경매 가격이 아닐 수 있다(미확인, 실측 필요) |
| IEX vs SIP 일봉 차이(FAQ 예시) | AAPL 2023-09-29: IEX 거래량 923,134 / 종가 **171.29**, SIP 거래량 51,861,083 / 종가 **171.21**. → IEX 일봉 종가는 통합 종가와 다를 수 있다 |
| 캘린더 | 레거시 `GET /v2/calendar`(KISTA 사용 중, 문서 URL이 `legacycalendar`)와 신형 `GET /v3/calendar/{market}`(`core_start/core_end/pre_*/post_*/lunch_*`, ISO8601 오프셋 시각, 조기 마감 전용 플래그 없음, base는 paper-api/api, 키 헤더, 분당 한도 예시 100) |
| 장 상태 | `GET /v3/clock?markets=NYSE` → `phase`(`closed/pre/core/lunch/post`), `phase_until`, `is_market_day`, `next_market_open/close`. 레거시 `/v2/clock`는 이번에 페이지를 못 읽음(404)이나 `legacyclock` 문서 존재 |
| 시간외 | 일봉 OHLC에는 시간외 체결 미반영. 프리·애프터 체결가를 latest trade/snapshot `latestTrade`가 포함하는지는 문서에서 확인 못 함(분봉에는 반영). 오버나잇은 유료·영업 문의 |
| 환율 | `GET /v1beta1/forex/rates`(과거, `5Sec/1Min/1Day`)·`/v1beta1/forex/latest/rates`(최신) 존재, 파라미터 `currency_pairs`. **베타 경로이며 지원 통화쌍·KRW 포함 여부는 문서에서 확인 못 함**(Local Currency Trading FAQ는 GBP·CHF·EUR·CAD만 언급 — 다른 상품) |
| 종목 | TQQQ·SOXL 등 ETF는 일반 NMS 종목이라 심볼 그대로(`TQQQ`). 기존 지수 ETF·백테스트가 이미 같은 방식으로 조회 중 |

### 2-4. 이용 조건

| 항목 | 확인 결과 |
|---|---|
| paper 키로 시세 | **가능**. 시세 API는 live·paper 모두 `data.alpaca.markets`. 무료 등급이 기본(2차 자료: paper 전용 계정은 이메일 가입만, 자금 불필요). KISTA 운영이 이미 이렇게 돌아간다 |
| 한국 서버·한국 거주 | 지역 차단·거주 요건 문서 못 찾음(**미확인**). 단 KISTA OCI 서버가 이미 `data.alpaca.markets`·`paper-api`를 호출 중이라 서버 IP 차단은 운영 사실상 없다 |
| 재배포·다수 사용자 표시 | 공식 지원 문서(2022-11): **"you cannot redistribute Alpaca API data."** 예외·조건 기술 없음. 포럼에는 "end users에게 표시 데이터" 마케팅 문구가 있으나 라이선스 문서 아님. 2차 자료(내부 메모 성격)는 SaaS 최종 표시는 서면 상업 부속계약 필요 가능성이 높다고 해석 — **공식 약관 원문으로 확정하지 못함**. Broker API가 최종 사용자 제품용이라는 설명도 2차. 이 이슈는 지수 ETF 종가를 DB에 저장해 벤치마크로 표시 중인 **현재 사용에도 이미 걸려 있다** |
| 지연 | 무료 feed iex는 실시간, sip 최근 15분 불가 |

## 3. 항목별 판정표

전제: 현재 운영 키는 **무료 Basic**. 등급 열은 "무료 / Algo Trader Plus($99)".

| 항목 | 현재 토스 | Alpaca 대응 API | 등급별 가능 여부 | 호출 한도 영향 | 판정 |
|---|---|---|---|---|---|
| 현재가 | `GET /api/v1/prices` 최대 200종목, 관리자 키, 15 TPS | `GET /v2/stocks/snapshots?symbols=…&feed=iex` → `latestTrade.p` (또는 `trades/latest`). 다중 심볼 1회 | 무료: **IEX 체결가만**(전체 거래량 2.5%) — 유동성 큰 TQQQ·SOXL은 대체로 신선하나 통합 시세와 달라질 수 있고 시간대별 신선도 미보장. 무료 `delayed_sip` 가능 여부 미확인. 유료: SIP 실시간 | 종목 6개를 1회 → 분당 200 대비 무시 가능 | **부분** — 무료는 IEX 가격(통합 시세와 불일치 가능). 관리자 시세는 표시용이므로 허용 가능한지 정책 판단 필요. 주문가 계산에 쓰이면 위험 |
| 전일 종가 | 일봉 `count=1` + 세션 판정 | snapshot `prevDailyBar.c`(무료는 iex feed 기준 = IEX 종가) 또는 bars `1Day&feed=sip&end=15분 이전`(무료 가능, SIP) | 무료 snapshot: IEX 종가(SIP과 다름). 무료 bars sip: SIP 종가, 15분 지연 | 1~2회 | **부분** — SIP bars로 받으면 가능. snapshot 한 번에는 IEX 종가 위험 |
| 확정 종가(마감 리포트·VR 롤오버·Mock OHLC) | 일봉 `from=to` | bars `1Day&adjustment=raw&feed=sip&start=end=거래일`(마감 후 15분 지난 뒤). 필드 `o,h,l,c` | 무료: SIP 일봉 가능(마감+15분 후). **일봉 close가 공식 종가(마감 경매가)인지 미확인** — FAQ상 official close 체결은 bar를 갱신하지 않음 | 종목당 1회 | **부분(검증 필수)** — 확정 종가 정확도가 핵심. 토스 값과 일치 표본 비교 전 대체 불가 |
| 일봉 캔들 — UI 최신 600봉·최대 5년 | 200봉/요청, `nextBefore` 페이징, UI는 raw | bars `1Day&adjustment=raw&feed=sip&limit=10000`, 단일 심볼 5년 1회 | 무료 가능(최근 15분 제외, 2016~). 일봉에 당일 진행봉 필요하면 iex 혼합 | 요청 수 감소(페이징 불필요) | **가능** (당일 진행봉만 IEX 한계) |
| 일봉 — DCA 지표(조정 주가) | `adjusted=true`(분할·배당 조정) | `adjustment=all`(split+dividend, `spin-off` 포함) 또는 `split,dividend` | 무료 가능 | 소량 | **가능** — 토스 `adjusted=true`와 수치 동치성은 표본 비교 필요 |
| 분봉(쓰는 경우) | `candles` interval 분 | bars `1Min`…`59Min`, `limit` 10000 | 무료: iex/sip(15분 지연) 가능 | 분당 200 | **가능**(현재 필수 소비처는 확인 못 함 — 사용처 점검 필요) |
| 환율 USD/KRW | `GET /api/v1/exchange-rate`(`rate`,`midRate`), 3 TPS, 60s 캐시 | `/v1beta1/forex/latest/rates?currency_pairs=…` 베타 | KRW 쌍 지원 여부 문서 미확인 | — | **불가(미확인)** — 현재 근거로는 환율은 토스/메리츠 유지 전제 |
| 장 상태·세션 | 코드 내 DST 표(토스 미사용) | `GET /v3/clock` `phase`·`phase_until`·`next_market_open/close` (paper-api/api, 키 헤더) | 무료·paper 키 가능 | 소량 | **가능** — DST 표 대체. 단 시간 정책(`Clock`·KST)과 호출 의존이 늘어남 |
| 휴장일 | Alpaca `/v2/calendar` 이미 사용 | 동일 | — | — | **이미 Alpaca**(토스 미사용). 신형 `/v3/calendar`는 세션별 시각 제공하나 현 레거시가 동작 중 |
| 관리자 시세 체인 | Toss → KIS | 현재가·전일종가·확정종가를 Alpaca 구현으로 교체 | 위 세 행에 종속 | — | **조건부** — 확정 종가 검증 통과 전엔 체인 유지 |
| Mock 시세 | `TossPriceApi.getClosingPrice/fetchConfirmedCandle` | bars `1Day` o,h,l,c | 위 확정 종가와 동일 | — | **부분** — OHLC 체결 판정은 통합(SIP) 일봉이면 충분할 수 있으나 검증 필요 |

## 4. 메리츠 안과의 비교

| 기준 | 메리츠 안(11절 8-1~8-5) | Alpaca 안 |
|---|---|---|
| 인증·계좌 종속 | 앱키 1개=계좌 1개, 토큰·`mac_address` 필수, 계좌 무관 공용 키 없음 → 운영자 계좌 키 필요 | **계좌와 무관한 키 1쌍**(paper 계정, 자금 불필요 — 2차). KISTA는 이미 운영 중 |
| 공용 키 확보 난이도 | 운영자 메리츠 계좌 앱키 + 사용자 한도·계좌 분리 설계 필요 | **0**(보유 키 재사용) |
| 단일 실패점 | 메리츠 관리자 앱키 장애=시세 전체 | Alpaca 장애=시세 전체(토스 폴백을 남기면 완화). 이미 캘린더·백테스트가 의존 중이라 의존 위치가 하나 더 쌓임 |
| 호출 한도와 매매 호출 분리 | 운영자 계좌를 매매에도 쓰면 110ms 게이트 공유 | **완전 분리**(매매 브로커와 무관). 한도 200/min이나 KISTA 사용량은 충분히 작음 |
| 캔들 범위·조정 주가 | 5년·600봉 1회 가능한지 미확인, 배당 조정 없음(분할만) | **1회 10000봉**, split·dividend·all 지원, 2016년 이후(5년 충족) |
| 확정 종가 신뢰도 | 증권사 원장 종가(`prpr`) — 원 주가 기준, 이미 계좌 단계에서 사용 | 무료는 **IEX/SIP 일봉 close**. IEX는 통합 종가와 다름(FAQ 예 0.08), SIP 일봉도 official close 체결을 bar에 반영하지 않는다고 FAQ가 밝힘 → **공식 종가와 일치 보장 없음**. LOC·MOC 체결 판정·VR 롤오버의 정확도 위험이 가장 큼 |
| 현재가 | 정규장 현재가 가능, 시간외 의미 불명 | 무료는 IEX 체결가(2.5%), SIP 현재가는 $99/월 |
| 시간외·24시간 | `prices/daytime`·`after` 별도 | 일봉은 정규장만, 오버나잇 별도 유료(영업 문의) |
| 환율 | `forex/rates`·`fx/prices` 존재(대체 가능) | **불명**(베타, KRW 미확인) — 환율은 Alpaca 불가로 가정 |
| 비용 | 0 | 무료 가능(현재가 IEX 한계 수용 시). 통합 시세 필요 시 **$99/월** |
| 약관·재배포 | 증권사 API 이용약관(미조사) | 공식 지원 문서 "재배포 불가", 다수 사용자 표시 허용 여부 **공식 확정 못 함**. 법적 리스크는 메리츠보다 불확실 |
| 이미 통합된 코드 | `MeritzPriceApi`가 계좌 단계에서 존재, 관리자 키 토큰 관리 신규 필요(앱키 단위 캐시) | Alpaca 어댑터 3벌(RestClient·헤더 코드 그대로). 신규는 snapshot·최신가 클라이언트 1개 + 확정 종가 조회 약 100~150줄 예상(3벌 중복 설정도 이 기회에 정리 가능) |
| 토스에서 지울 수 있는 코드 | 8-5: B≈395줄, A≈685줄 | 아래 조합안 참고(최대 A와 동일 685줄 중 환율 관련 약 100줄은 불가) |

## 5. 조합안 (11절 8-5 줄 수 재사용, 테스트 제외)

| 조합 | 구성 | 토스 관리자 키 의존 | 삭제 가능 줄 수(추정) | 비고 |
|---|---|---|---|---|
| α. Alpaca 캔들만 | 일봉 캔들(UI·DCA)만 Alpaca bars로 | **유지**(현재가·전일·확정·환율) | 약 195(`TossCandleApi` 174 + `TossCandle` 21) | 위험 최소. 일봉 항목은 판정 "가능" |
| β. 캔들 + 현재가 + 전일종가는 Alpaca, 확정 종가·환율은 메리츠/토스 | 표시용 값만 Alpaca | 확정 종가 공급자에 따라 다름(메리츠면 토스 관리자 키 제거 가능) | 약 195 + `TossPriceApi` 일부(현재가·세션 캐시 약 150) + `TossAdminPriceSource` 59 ≈ 400 | 토스 관리자 키를 완전히 지우려면 확정 종가·환율을 메리츠로 옮겨야 함 |
| γ. Alpaca 전부(환율만 토스) | 현재가·전일·확정·캔들·Mock | **유지**(환율 `exchange-rate` 때문에 관리자 토큰 필요) | 약 585(`TossPriceApi` 255, `TossAdminPriceSource` 59, `TossCandleApi`+`TossCandle` 195, `TossBrokerAdapter` 가격 위임 45, 관리자 토큰 경로 중 일부) | 관리자 토큰 경로 55줄과 `TossHttpClient.getCommon` 등은 환율용으로 남음. 확정 종가 정확도 위험이 가장 큼 |
| δ. 캔들·현재가·전일은 Alpaca, 확정 종가·환율은 메리츠 | Alpaca + 메리츠 관리자 키 | **제거 가능** | 약 685(A와 동일) | 메리츠 관리자 앱키 신규(토큰 캐시 필요) + Alpaca 신규 어댑터. 단일 실패점이 2곳으로 분산, 확정 종가는 증권사 원장 값 유지 |
| ε. 현행 유지 | — | 유지 | 0 | |

환율은 어느 조합이든 Alpaca가 아니라 토스 또는 메리츠(`forex/rates`·`fx/prices`)가 담당한다.

## 6. 운영 서버 읽기 전용 실측 목록 (실제 호출은 하지 않았음)

1. 현재 키로 `GET /v2/stocks/snapshots?symbols=TQQQ,SOXL,...&feed=iex` — `latestTrade.t` 지연, 정규장·프리·애프터·야간 시간대별 값, `prevDailyBar.c`가 직전 정규 종가인지(18:00·05:00~06:00 KST 경계 포함).
2. 같은 호출에 `feed=delayed_sip`·`feed=sip` — 무료 키에서 403 여부(무료로 15분 지연 SIP 현재가가 되는지).
3. `GET /v2/stocks/bars?symbols=TQQQ&timeframe=1Day&adjustment=raw&feed=sip&start=5년 전&end=어제&limit=10000` 1회 — 응답 봉 수·`next_page_token` 여부·소요 시간.
4. 표본 5거래일·5종목 이상: Alpaca SIP `raw` 일봉 `c`와 토스 확정 종가(현재 `TossPriceApi.fetchConfirmedCandle`)·KIS/메리츠 종가 일치율. 같은 날 IEX 일봉 `c` 차이도 기록. 반일 조기 마감일 포함.
5. 마감 직후 몇 분 뒤부터 SIP `1Day` 확정 봉이 조회되는지(마감+15분 제약 포함, 마감 리포트·VR 롤오버 실행 시각 대비 충분한지).
6. `adjustment=all` 일봉 vs 토스 `adjusted=true` 일봉의 수치 차이(DCA 지표 입력 시리즈).
7. `GET /v3/clock?markets=NYSE`(paper 키)의 `phase`·`phase_until`이 코드 DST 표와 일치하는지, 조기 마감일 `core_end`.
8. `/v1beta1/forex/latest/rates?currency_pairs=USD/KRW` — 지원 여부·베타 응답.
9. 응답 헤더 `X-RateLimit-Limit/Remaining`로 실제 분당 한도 확인.
10. (비호출) `ALPACA_API_KEY` 계정의 Alpaca 대시보드에서 구독 등급·시세 약관 동의 이력 확인.

## 7. 불명확·확인 못 한 것

- **다수 사용자에게 시세를 표시하는 서비스에 Alpaca 키를 쓰는 것의 약관 허용 여부** — 공식 지원 문서는 "재배포 불가"만 말하고 예외·표시 권한을 정하지 않는다. 약관·Market Data Agreement 원문은 이번에 읽지 못했다. Alpaca에 직접 문의가 필요(지수 ETF 종가를 DB에 저장·표시하는 현재 사용도 같은 이슈).
- 무료 등급에서 latest/snapshot의 `delayed_sip` 사용 가능 여부.
- 일봉 close가 공식 종가(마감 경매)와 일치하는지(FAQ는 official close 체결이 bar를 갱신하지 않는다고 명시).
- 프리·애프터 체결가가 `latestTrade`에 포함되는지, 오버나잇 구독 가격.
- 한국 거주·한국 서버 지역 제한(문서 미발견). 서버는 현재 호출 성공 중.
- 최대 심볼 수(snapshot·latest), 분당 한도가 snapshot·bars에 동일 적용되는지(문서 표는 "historical API calls 200/min").
- Forex 최신 환율의 지원 통화쌍(KRW 포함 여부)과 베타 안정성.
- 레거시 `/v2/calendar`·`/v2/clock`의 종료 예고(문서 URL이 `legacy*`) 여부 — 신형 `/v3/*`는 `core_start/core_end`로 조기 마감 표기가 다르므로 이행 시 어댑터 수정 필요.
- 월 구독료: 공식 표는 $99, 2차 요약은 $9 — 공식 표를 채택했으나 가입 화면에서 재확인 필요.
- 이번에 직접 읽지 못한 페이지(404): `/v2/calendar` 상세 레거시 페이지, `latestrates-1`, `getclock` 레거시, 시세 FAQ의 rate limit 항목.
