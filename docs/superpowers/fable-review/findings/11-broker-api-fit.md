# 11. 브로커 API 적합성 조사 (메리츠·토스 공식 스펙 대 현재 어댑터)

조사일 2026-11-12, 읽기 전용. 근거 문서: 메리츠 `meritz_openapi_spec_20261112.json`(OAuth 2 + 계좌 6 + 국내 30 + 해외 35 + 실시간 14), 토스 `openapi.json` v1.2.28(`/Users/phs/Downloads/toss/*`는 md5 동일한 전체 스펙 사본 12개, `conditionalOrderHistory`만 크기가 다르다). 공식 스펙 인용은 "스펙", 운영 실측은 `docs/agents/*-api.md`의 기록이다. 코드 경로 접두어 `B/` = `trading-core/src/main/java/com/kista/tradingcore/broker/`, `T/` = `.../tradingcore/trading/application/service/`.

범례: ✔ 일치 / △ 부분 일치·주의 / ✘ 불일치·의심 / ? 문서로 확인 불가.

## 1. KISTA 필요 기능 목록 (포트 전수)

`BrokerRouter`(`B/application/service/BrokerRouter.java`)가 공통 6개 포트를 계좌 브로커로 위임한다. 현재가·전일종가·확정종가는 관리자 시세(`AdminMarketPricePort`, Toss 관리자 키 → KIS 관리자) 우선, 누락분만 계좌 어댑터.

### 1-1. 계좌 브로커 공통 6포트 (`BrokerCapabilitiesPort`)

| 포트.메서드 | 시그니처 | 호출처 (file:line) | 용도 | 상태 |
|---|---|---|---|---|
| PortfolioPort.getPresentBalance | `(BrokerAccountRef) → PresentBalanceResult` | `stats/application/service/AccountStatisticsService.java:48` | 계좌 대시보드(보유 종목+예수금 요약) | 사용 |
| MarginPort.getUsdBuyableAmount | `(BrokerAccountRef) → BigDecimal` | `T/support/UnassignedCashCalculator.java:65`, `T/TradingPreviewService.java:223` | 매수 가능 USD(미배정 현금·미리보기) | 사용 |
| MarginPort.getMargin | `(BrokerAccountRef) → List<MarginItem>` | 없음(어댑터·라우터 위임만, 테스트 외 호출 0) | — | **미사용** |
| SellableQuantityPort.getSellableQuantity | `(ticker, ref) → SellableQuantity` | `T/TradingPreviewService.java:253` | 수동 SELL 미리보기 검증(보유 수량) | 사용(1곳) |
| BrokerOrderCorrectionPort.place | `(OrderInstruction, ref) → OrderResult` | `T/OrderSubmitter.java:65`, `T/OrderSubmissionReconciler.java:78` | 주문 접수(LOC·MOC·LIMIT·MOO) | 사용 |
| BrokerOrderCorrectionPort.cancel | `(CancelInstruction, ref) → CancelOutcome` | `T/OrderSubmitter.java:131`, `T/OrderCancelService.java:119,200`, `T/TradingReporter.java:98` | 주문 취소(마감 전 잔여 PLACED 명시 취소 포함) | 사용 |
| ExecutionPort.getExecutions | `(from, to, ticker, ref) → List<Execution>` | `T/TradingReporter.java:58`, `T/CancelledOrderFillRecorder.java:38` | 마감 체결 조회·원장 기록 | 사용 |
| BrokerPricePort.getPrice | `(ticker, ref) → BigDecimal` | `T/TradingPriceFetcher.java:157`, `T/VrReconfigureService.java:99` | 현재가 단건 | 사용 |
| BrokerPricePort.getPrices | `(List, ref) → Map` | `T/TradingPriceFetcher.java:156`, `T/AccountLedgerTotalsService.java:56`, `stats/.../StatisticsController.java:88`(AccountStatisticsService 경유) | 현재가 일괄 | 사용 |
| BrokerPricePort.getPrevClose | `(ticker, ref) → BigDecimal` | `T/TradingPriceFetcher.java:67,139`, `T/InfiniteMinSeedGuard.java:31`, `T/support/StrategyHistoryQueryService.java:78`, `T/StrategyCreationService.java:159` | 전일 종가(주문가 산정 기준) | 사용 |
| BrokerPricePort.getPrevCloses | `(List, ref) → Map` | `T/TradingPriceFetcher.java:66,138`, `stats/.../AccountStatisticsService.java:96` | 전일 종가 일괄 | 사용 |
| BrokerPricePort.getClosingPrice | `(ticker, tradeDate, ref) → BigDecimal` | `T/TradingPriceFetcher.java:147`, `T/VrCycleRolloverService.java:84` | 정규장 확정 종가(마감 리포트·VR 롤오버) | 사용 |
| BrokerPricePort.getClosingPrices | `(List, tradeDate, ref) → Map` | `T/TradingPriceFetcher.java:146` | 확정 종가 일괄 | 사용 |
| BrokerPricePort.getPriceSnapshot / getPriceSnapshots | `→ PriceSnapshot(current, prevClose)` | 없음(테스트의 `never()` 검증만) | — | **미사용**(두 포트 메서드와 모든 어댑터 구현·`PriceSnapshot` 타입) |

### 1-2. 통계·시세 capability (`BrokerStatisticsPort` = Toss 전용)

| 포트.메서드 | 호출처 | 용도 | 상태 |
|---|---|---|---|
| CandlePort.getLatestCandles(symbol, interval, count) | `B/adapter/in/web/CandleInternalController.java:28` ← 루트 `CandleQueryHttpAdapter.java:34` ← `MarketHolidayController:/candles` | UI 캔들 차트 | 사용(HTTP 경유) |
| CandlePort.getCandles(symbol, interval, from, to) | `CandleInternalController.java:37` ← 루트 `CandleQueryHttpAdapter.java:48` | UI 기간 캔들 | 사용 |
| CandlePort.getDailyCandlesBefore(symbol, beforeUsSession, count) | `T/DcaPlanInputCollector.java:142` | DCA 지표용 일봉(코인 DCA 경로가 아니라 주식 DCA) | 사용 |
| ExchangeRatePort.getExchangeRate() | `stats/application/service/BrokerStatisticsService.java:23` ← 루트 `CurrentExchangeRateHttpAdapter.java:26` | 벤치마크 비교의 현재 환율 | 사용 |

### 1-3. 기타 브로커 포트

| 포트.메서드 | 호출처 | 용도 | 상태 |
|---|---|---|---|
| BrokerConnectionTestPort.verifyCredentials | `account/application/service/AccountService.java:204` | 키 검증(토큰 발급 시도) | 사용 |
| BrokerConnectionTestPort.verifyAccount | `AccountService.java:95,147` | 계좌 등록·키 교체 시 계좌 검증 | 사용 |
| AccountTokenEvictionPort.evict | `AccountService.java:168,189`, `AccountDeletionWriter.java:26` | 키 교체·삭제 시 토큰 캐시 제거 | 사용 |
| AdminMarketPricePort.getPrices / getPrevCloses / getClosingPrices | `BrokerRouter`만 | 관리자 키 시세 1단계 | 사용 |
| BrokerTokenCachePort(4메서드) | 메리츠 `DbTokenCoordinator`(KIS와 공용) | broker_tokens DB 캐시 | 사용(메리츠가 KIS 삭제 후 유일 소비자가 됨) |
| MockSimulationDataPort | Mock 어댑터 전용 | 모의 체결 시뮬레이션 | 사용(Mock) |
| CryptoExchangePort | 코인 DCA(바이낸스·업비트) | 범위 밖 | — |

요약: 주식 경로 호출 가능 기능은 **조회 7종(잔고요약·매수가능USD·보유수량·체결·현재가·전일종가·확정종가) + 주문 2종(place·cancel) + 계좌검증 2종**이다. 미사용 포트 메서드는 `getMargin`, `getPriceSnapshot(s)` 3개(+`MarginItem`·`PriceSnapshot` 타입, 각 어댑터 구현).


## 2. 메리츠 대조표

코드: `B/adapter/out/meritz/`(M/)의 `MeritzHttpClient`·`MeritzAuthApi`·`MeritzOrderApi`·`MeritzTradingApi`·`MeritzPriceApi`·`MeritzResponse`·`MeritzExchangeRegistry`. 스펙에는 **오류 코드표가 없다**(2-4 참조) — 코드 판정표(`MeritzResponse` SUCCESS/TOKEN_INVALID 등)는 전부 실측 기반이다.

### 2-1. 기능별 대조

| KISTA 기능 | 현재 호출 (엔드포인트, file:line) | 스펙 (필수 파라미터·응답 핵심·제약) | 일치 | 문서에 있는 더 적합한 API | 코드가 스펙과 어긋나는 점 |
|---|---|---|---|---|---|
| 주문 접수 place | `POST /trading/v1/overseas/orders/{buy|sell}` `M/MeritzOrderApi.java:66,88` body `ovrs_stck_symb_iscd, ovrs_exch_vndr_idno, oder_cls_code, oder_cond_cls_code, odqt, ovrs_stck_oder_prc` | 필수 `ovrs_stck_symb_iscd, ovrs_exch_vndr_idno, oder_cls_code, odqt(Number), ovrs_stck_oder_prc(String)`, 선택 `oder_cond_cls_code, warn_cnfr_yn`. 가격 소수 4자리 초과 시 `IGW50002`. 응답 `data{oder_no(Number), warn_cls_code, warn_msg}`, 성공 `rsp_cd=0001`, 연속조회 없음 | ✔ (유형 코드 전부 스펙에 존재: 01 지정가·05 시장가·31 LOO·32 LOC·33 MOO·34 MOC) | `31`(LOO)·`33`(MOO)는 스펙에 정식 존재 → 현재 MOO를 "개장 후 지정가/시장가 근사"로 처리(`MeritzOrderApi.java:78`)하는 것은 스펙 한계가 아니라 KISTA 선택 | `odqt`·`oder_no`를 JSON 문자열로 송신(스펙·예시는 Number). 실주문 실측(LOC·지정가)은 통과해 무해하나 스펙 타입과 다름. `oder_cond_cls_code`는 선택(`01/05→0`, `31/33→2`, `32/34→7`로 유형에서 결정) — 코드는 매핑표를 `m[1]`로 항상 송신 |
| 주문 취소 cancel | ① `GET .../orders/today`(`cntg_cls_code=0, seln_byov_clcd=0`, 연속조회) → 없으면 ② `GET .../orders/history`(최근 8일) → ③ `POST .../orders/cancel` `M/MeritzOrderApi.java:97-115,145-167` | 취소 필수 `oder_date, orgl_oder_no, oder_cls_code(원주문 그대로), odqt`. 응답 형식은 접수와 동일(새 `oder_no`) | ✔ (취소 body 일치) | `GET /trading/v1/overseas/orders/detail`(`oder_sta_date`+`oder_no` 필수, 1건 조회): 호출 1회로 원주문 확인 가능. 단 행이 **체결 건별**이라 합산 필요하고 `oder_able_qty`가 없어 잔량은 `oder_dqty-cntg_dqty`로 계산해야 함 → 현 today 목록 방식도 타당(트레이드오프) | **✘ 의심**: 기간 목록 폴백이 취소 가능 수량으로 `ncnt_odqt`를 씀(`MeritzOrderApi.java:61,163`). 스펙은 history의 `ncnt_odqt`를 "실제 미체결과 다를 수 있으니 `oder_dqty-cntg_dqty`로 계산"하라고 명시. 오값 0이면 살아 있는 주문을 ALREADY_FILLED/CANCELED로 오판해 취소를 건너뜀(애프터장 체결 누락 방어선 약화). 당일 경로의 `oder_able_qty`는 스펙상 "정정·취소 가능 수량"이라 적합 |
| 체결 조회 getExecutions | `GET .../orders/history`(`oder_sta_date, oder_fin_date, cntg_cls_code=0`, 연속조회) `M/MeritzTradingApi.java:109-125` | 필수 `oder_sta_date, oder_fin_date, cntg_cls_code`(0 전체·1 체결·2 미체결). 30건/페이지, `tr_cont=1`+`tr_cont_key`. 행: `cntg_dqty`(체결수량, history는 주문당 1행), `cntg_crnc_prc`, `cntg_camt`, `iscd`(접미사 포함), `oder_rctf_cncl_cls_code` | ✔ | `cntg_cls_code=1`(체결만): 체결 0건 행을 서버에서 걸러 페이지 수 감소(코드는 `0`으로 받아 `qty>0`을 클라이언트 필터, "1이 부분체결 후 만료 건을 주는지 미실측"이라 보류) | 종목별 호출이라 같은 계좌·같은 날짜 범위를 종목 수만큼 반복 조회하고 클라이언트에서 `iscd` 필터(스펙에 종목 필터 파라미터 없음 — 호출부에서 계좌당 1회로 모으는 구조 개선 여지). `oder_date`를 US 거래일로 해석 ✔(실측) |
| 잔고 요약 getPresentBalance | `GET .../me/overseas/holdings`(연속조회) + `GET .../me/overseas/currencies` + `GET .../orders/estimate`(KRW 행 예수금) `M/MeritzTradingApi.java:90-106` | holdings: 30건/페이지, **`rmnd_clcd` 1 일반·2 소수·3 대출**(같은 종목이 여러 행 가능), `rmnd_dqty`(소수 10자리 문자열), `avrg_unpr, psnt_prc`. currencies: 통화별 1행 `crcd, frcr_amt, estm_frcr_amt, vltn_exrt` | △ | currencies가 이미 USD·KRW 행을 주는지 확인되면 estimate 호출 1회 생략 가능(? KRW 행 `frcr_amt`=KRW 예수금 여부 미확인). `GET /accounts/v1/me/valuation`(한 줄 자산 요약)은 국내 포함 합산이라 USD 전용 요약엔 부적합 | **△ 의심**: ① 같은 종목의 일반+소수 행을 합치지 않음(`MeritzTradingApi.java:138` `findFirst`, `:92` 행별 add) — 소수주 보유 시 수량 과소·종목 중복. ② `wholeQuantity`가 소수부 버림(`MeritzResponse.java:75`) — 소수주 잔량 손실. KISTA 주문이 정수 주식뿐이라 현재는 무해, 외부 소수 매매·분할·배당 재투자가 있으면 어긋남(미실측) |
| 매수 가능 USD getUsdBuyableAmount | `GET .../orders/estimate`(파라미터 없음) → 전 행 `oder_crnc_oder_able_camt` 합 `M/MeritzTradingApi.java:71,146` | 선택 `intg_marg_iqry_yn`(Y 통합증거금 기준/N 일반). 행: `crcd, stnd_deps_camt, exrt, oder_crnc_oder_able_camt`(주문통화 기준), `pcur_oder_able_camt`(=×exrt 원화). **주문가능수량은 오지 않음**(종목·단가를 받지 않아 직접 계산) | △ | 해당 용도엔 이 API가 유일 | `intg_marg_iqry_yn` 미송신 → 기본값이 문서에 없음(? 실측 4501.94+3577.99 합이 USD 환산이라는 근거는 실측뿐, 스펙은 "주문 통화 기준"만 서술). 파라미터를 명시하면 통합증거금 설정 변동에 둔감해짐 |
| 판매 가능 수량 getSellableQuantity | `GET .../me/overseas/holdings` 후 `rmnd_dqty` `M/MeritzTradingApi.java:85` | `rmnd_dqty`=보유수량, `seln_able_dqty`=매도가능수량(실측: 미체결 매도 차감) | ✔ (포트 계약상 의도적으로 `rmnd_dqty`) | — | holdings 전체 연속조회로 1종목 수량만 취함(요청 필터 없음, 스펙에도 종목 파라미터 없음). 소수/대출 행 합산 문제는 위와 동일 |
| 현재가·전일종가 | `GET /market/v1/overseas/prices`(`mrkt_div_code=OV, mrkt_cls_code, iscd, dely_rltm_cls_code=0`) → `prpr`, `prdy_clpr` `M/MeritzPriceApi.java:46-60` | 필수 4개 모두. **`iscd`에 접미사(예 `AAPL.OQ`)를 넣으면 오류 없이 `rsp_cd` 정상·가격 0**으로 내려옴. 성공 `rsp_cd=0000`. 단건만(배치 없음). 주간·애프터는 별도 `prices/daytime`·`prices/after` | ✔ (순수 심볼 사용, 가격 ≤0 검사로 방어 — 스펙의 함정을 이미 막음) | `prices/after`(애프터장 현재가): 마감 후 가격이 필요하면 별도 API(현 코드는 사용 안 함; 관리자 시세는 토스 우선이라 영향 작음) | 종목별 순차 호출(`perTicker`) — 일괄 API 없음, 110ms 게이트로 N×110ms. 응답 중 사용 필드는 `prpr, prdy_clpr` 둘뿐(레코드도 2필드) |
| 확정 종가 | `GET /market/v1/overseas/candles/days`(+`mod_stpr_cls_code=1, from=to=US거래일`) `M/MeritzPriceApi.java:98-129` | `mod_stpr_cls_code` 필수: **`0`=분할 반영 보정 주가, `1`=보정 전 원래 주가**. `from`·`to` 함께, 미입력 시 최신 40건, 최근 일자가 앞. 행 `date, prpr(종가), oprc, hprc, lprc, acml_vol` | △ | — | **코드 주석 오류**: `"1" // 수정주가 반영`(`MeritzPriceApi.java:106`)은 스펙과 반대(`1`=원 주가). 동작(실제 체결 종가=원 주가)은 오히려 맞을 가능성이 높으나, 주석·`meritz-api.md`가 오해를 부른다. 분할 발생일 전후 종가 비교가 필요한 지표라면 `0` 필요(KISTA 용도는 당일 종가 단건이라 `1`이 타당). 봉 날짜 불일치 시 라이브가 폴백(`:120`) 방어는 합리적(스펙에 휴장일 봉 처리 규정 없음) |
| 계좌 검증 verifyAccount | `POST /oauth2/token` 후 `GET .../me/overseas/currencies` 성공 여부 `M/MeritzAuthApi.java:73-92` | currencies는 요청 파라미터 없음, 성공 `rsp_cd=5766`/`5820` | ✔ | — | `5766`/`5820`을 모두 성공으로 보는 SUCCESS_CODES 합집합 사용 — 스펙에는 API별로 성공 코드가 다름("0000만으로 판정 금지") |

### 2-2. 인증

| 항목 | 스펙 | 현재 코드(`MeritzAuthApi.java:56-131`) | 평가 |
|---|---|---|---|
| 발급 | `POST /oauth2/token` form `grant_type=client_credentials, client_id, client_secret, scope=login` | 동일 | ✔ |
| 응답 필드 | `access_token, expires_in(String, 유효기간 초), scope, token_type`. **`expires_date` 없음**. 예시 `expires_in: 604800`(7일) | `expires_date`("yyyy-MM-dd HH:mm:ss" KST)를 필수로 파싱, 없으면 발급 실패 처리(`:119-130`). `expires_in`은 무시 | ✘ 스펙 밖 필드에 의존. 문서는 7일(604800), 실측은 `expires_in=43200` 고정에 `expires_date` 별도 존재. 게이트웨이가 `expires_date`를 빼면 전 계좌 발급 실패 → `expires_in` 폴백 없음 |
| 갱신/만료 규칙 | 스펙에 없음(재발급 시 동일 토큰 반환 여부도 미기재) | "만료 전 재발급은 같은 토큰·같은 `expires_date`(멱등)" — 실측. DB 캐시(`broker_tokens`) + JVM 락(`DbTokenCoordinator`), 만료 1분 전 무효, 토큰 거절 시 1회 재발급 | △ 실측 의존. 재발급이 비파괴이므로 Toss와 달리 분산 조정이 불필요하다는 판단은 합리적 |
| 폐기 | `POST /oauth2/revoke`(`client_id, client_secret, token_type_hint=access_token, token`) | 호출 금지(같은 앱키의 유일 토큰을 끊는다는 실측) | △ 스펙은 이 부작용을 쓰지 않음 |
| 필수 헤더 | `authorization: Bearer`, `mac_address`(12자리, "넣지 않으면 거부") | 고정 상수 `0242AC110002` | ✔ |

### 2-3. 호출 제한·주문 속성

| 항목 | 스펙 | 코드/문서 | 평가 |
|---|---|---|---|
| rate limit | `EGW00200`="초당 호출 한도 초과, 잠시 뒤 재시도". **수치 없음**(TPS·분당 한도 어디에도 없음) | 앱키별 최소 간격 110ms(≈9 TPS) 게이트(`MeritzHttpClient.java:44`), GET만 1s·2s 백오프 2회, 문서는 "API별 10 TPS" | △ 110ms의 근거는 스펙에 없다(문서의 "10 TPS"는 포털/실측 출처 불명 — ? 확인 필요). 게이트는 한도를 선제 회피하는 보수책으로 타당하나 근거 서술을 "실측·보수적 설정"으로 고쳐야 함 |
| 주문 유형 | 01 지정가·05 시장가·31 LOO·32 LOC·33 MOO·34 MOC·35~38 VWAP/TWAP, 정정(`/modify`)·취소 지원 | place는 LOC·MOC·LIMIT·MOO(근사)만 | ✔ 필요한 것만. `modify`·VWAP/TWAP 불필요 |
| 프리/애프터 주문 | **주문 API에 세션·시간 규정 없음**. 시세는 주간(`prices/daytime`)·애프터(`prices/after`) 별도 | KISTA는 정규장 안 접수만 | ? 프리·애프터 주문 가능 여부 문서 없음 |
| 주문 가능 시간 | 문서에 없음. 장 상태는 `GET /reference/v1/overseas/market-hours`(`ovrs_mkop_cls_code` 004 정규장 개시 등, KST HHMM)·`market-days`(`bsop_date`)로 조회 | 사용 안 함(`marketcalendar` 모듈은 Alpaca 등 별도 출처) | 해외 영업일·장 시각은 메리츠 API로 대체 가능(KISTA 요구와 맞는지는 별도 판단) |
| 소수점 주식 | history·detail 주석에 "소수점 주문을 지원"(`oder_dqty` 소수 자릿수), holdings `rmnd_clcd=2` 소수. 주문 API의 소수 수량 입력 규칙은 **문서에 없음** | 정수 수량만 송신, 수신은 `wholeQuantity`로 버림 | ? 주문 시 소수 수량 가능 여부 불명 |
| 멱등성·중복 방지 | **클라이언트 주문 ID·멱등 키 없음**("멱등"·"중복"·"client_order" 문서 내 0건) | POST 재시도 없음(토큰 거절 1회·경고 확인 1회 제외) | ✔ 합리적. 접수 불명(통신 실패)은 `rejected=false`로 분류해 사후 대사로 넘김 |
| 체결 조회 방식 | 폴링: `orders/today`(당일)·`orders/history`(기간)·`orders/detail`(주문 1건, 체결 건별 행·`cntg_no`). 실시간: WebSocket "주문 접수·체결 통보"(해외주식 `주문 접수·체결 통보`) | history 폴링(마감 리포트 배치) | ✔ KISTA는 배치형이라 폴링이 적합. WebSocket 통보(접수 통보로 `SUBMITTING` 잔류를 줄일 수 있음)는 상시 연결·재연결 복잡도 대비 이득 작아 비권장 |
| 정정 | `POST .../orders/modify`(가격·수량) | 사용 안 함(취소 후 재접수 방침) | 불필요 |

### 2-4. 오류 코드표

스펙에는 오류 코드표가 **없고** 아래만 언급된다. 나머지(`EGW00121/123/103`, `IGW50024`, `1543`, `5988`, `5762`의 일부 의미)는 전부 KISTA 실측이다.

| 코드 | 스펙 서술 | KISTA 처리 | 비고 |
|---|---|---|---|
| `0001` | 주문 접수 성공 | 성공 | ✔ |
| `0000` | 현재가·일봉 성공. "일부 오류는 `rsp_cd`가 `0000`으로 내려온다"(market-hours 등 data 없음) | 성공 | `data` null을 호출부가 처리(현재가는 ≤0이면 예외) ✔ |
| `5766` | 조회 완료(성공) | 성공 | ✔ |
| `5762` | 조회 결과가 더 있음(연속조회) | 이어서 조회 | ✔ |
| `5820` | 조회할 자료 없음(오류 아님, 0건) | 성공 | ✔ |
| `5822` | 조회할 자료 없음(국내 일부) | 성공 | 해외 경로엔 해당 API 없음 |
| `5620` | 국내 예약주문 응답 예시에만 등장 | 성공 목록에 포함 | 해외 경로엔 해당 없음 → SUCCESS_CODES에서 제거 가능 |
| `5806` | 조회 오류(벤더 코드 `0000` 등) | 실패 | ✔ |
| `3174` | 시작일자 > 종료일자 | 실패 | ✔ |
| `0760` | 계좌에 이 조회 권한 없음 | PERMISSION_DENIED | ✔ |
| `EGW00200` | 초당 호출 한도 초과 | GET 백오프, POST 즉시 `BrokerRateLimitException` | ✔ |
| `IGW50002` | 가격 소수 4자리 초과·특정 파라미터 오류 | 일반 거절 | 코드가 2자리 반올림이라 미발생 |
| `EGW00202` | **WebSocket 헤더 `Bearer ` 접두어 누락**으로 거절 | **REST에서 "권한 없음"으로 분류**(`MeritzResponse.java:31`) | ✘ 스펙은 다른 의미. REST 권한 오류 분류는 실측으로 근거를 다시 확인하거나 `0760`만 남길 것 |
| HTTP | 모든 오류가 HTTP 500 | 본문 `rsp_cd` 우선 판정 | ✔ 스펙과 부합 |

### 2-5. 문서에 있으나 KISTA가 안 쓰는 API 중 쓸 만한 것

| API | 가치 | 판단 |
|---|---|---|
| `GET /trading/v1/overseas/orders/detail` | 주문 1건·체결 건별 행·거부 사유 | 취소 전 원주문 조회 대체 가능하나 `oder_able_qty` 없음. 현 구조 유지 권장, 폴백 경로의 잔량 산식만 교체 |
| `GET /reference/v1/overseas/market-hours`, `market-days`, `exchanges` | 장 상태·영업일·거래소 코드표 | 메리츠 주력 전환 후 휴장 판단을 단일 출처로 줄이는 데 후보(미검증: 휴장일 목록이 아니라 "현재 영업일" 단건) |
| `GET /forex/v1/rates`(고시환율, `stnd_date` 필수), `GET /market/v1/overseas/fx/prices`(`iscd=USDKRWSMBS`) | USD/KRW 환율 | 환율은 토스가 공급하기로 한 결정과 중복. 메리츠 장애 대비 2차 출처 후보 |
| `GET /accounts/v1/me/overseas/profit-loss` | 해외 실현손익(매도 건별) | 통계 기능이 필요할 때만 |
| `GET /trading/v1/overseas/orders/estimate`의 `intg_marg_iqry_yn` | 증거금 기준 고정 | 파라미터 명시 권장(위 표) |
| WebSocket 해외 주문 접수·체결 통보 | 실시간 체결 | 비권장(위) |

## 3. 토스 대조표

코드: `B/adapter/out/toss/`(T/)의 `TossHttpClient`·`TossAuthApi`·`TossOrderApi`·`TossHoldingsApi`·`TossPriceApi`·`TossCandleApi`·`TossDistributedTokenCoordinator`·`TossRedisTokenStore`. 스펙 출처는 OpenAPI v1.2.28과 개요 문서(`https://openapi.tossinvest.com/openapi-docs/overview.md` WebFetch로 보충). 개요 문서의 TPS 수치는 웹 요약본이라 변경될 수 있다("한도는 예고 없이 변경, `X-RateLimit-Limit` 헤더가 현재값").

### 3-1. 기능별 대조

| KISTA 기능 | 현재 호출 (file:line) | 스펙 | 일치 | 문서에 있는 더 적합한 API | 코드가 스펙과 어긋나는 점 |
|---|---|---|---|---|---|
| 주문 접수 place | `POST /api/v1/orders` body `symbol, side, orderType=LIMIT, timeInForce, quantity, price, clientOrderId` `T/TossOrderApi.java:41-66` | `orderType` LIMIT·MARKET, `timeInForce` DAY·CLS·OPG. **CLS는 미국+LIMIT만(=LOC)**. 수량은 정수(소수 수량은 미국 시장가 매도만), 가격 $1 이상 소수 2자리·$1 미만 4자리(초과 절삭). `clientOrderId` 36자 `^[a-zA-Z0-9\-_]+$`, 10분 멱등. 응답 `result.orderId` | ✔ | **`orderType=MARKET`(미국 시장가)이 스펙에 있다.** 코드 주석 "Toss는 MARKET 주문 미지원"(`TossOrderApi.java:42`)과 매도 MOO/MOC의 $0.01 지정가 우회(`:185`)는 스펙과 어긋난 서술. MOC는 `MARKET+CLS`가 불가라 `LIMIT+CLS @0.01`(LOC로 종가 경매 참여)이 유일한 근사 → 유지. 매도 MOO(개장 후 즉시 매도)는 `MARKET+DAY`로 대체 가능 | `quantity`·`price`를 JSON 숫자로 송신(스펙은 decimal 문자열, 실측 통과). 가격 자릿수 규칙(2/4자리)을 호출부 반올림에 맡김 — 상위에서 `HALF_UP`로 반올림하는데 토스는 절삭이라 경계 1틱 차이 가능(? 미실측) |
| 주문 취소 cancel | `POST /api/v1/orders/{orderId}/cancel` body `{}` `T/TossOrderApi.java:68-81` | 응답 `result.orderId`(취소가 **새 주문ID** 발급). 409 `already-filled`/`already-canceled`/`already-processing`, 422 `cancel-restricted` | ✔ (409 두 코드만 정확 매칭) | — | `already-processing`(409)·`cancel-restricted`(422)는 별도 분류 없음(일반 확정 거절로 처리). 취소 응답의 새 orderId는 버림(문제 없음 — 체결은 원주문 `execution`으로 조회) |
| 체결 조회 getExecutions | `GET /api/v1/orders?status=CLOSED`(+cursor, limit=100) 와 `status=OPEN`, `symbol`, `from=D-1`, `to` `T/TossOrderApi.java:83-135` | 두 status 모두 필수. OPEN은 대기 주문 전량(limit·cursor 무시, from/to는 `orderedAt` KST). CLOSED는 `limit` 최대 100·`cursor`. `execution{filledQuantity, averageFilledPrice, filledAmount, filledAt(KST ISO8601)}` | △ | `GET /api/v1/orders/{orderId}`: 외부 주문번호를 이미 아는 경우 주문 1건을 정확 조회(날짜 윈도·`D-1` 우회 불필요). 현재는 종목+날짜 목록을 긁어 클라이언트에서 재필터 | **△ 의심**: 스펙상 `PARTIAL_FILLED`가 OPEN과 CLOSED 양쪽 상태 집합에 모두 적혀 있는데 코드는 두 목록을 합치되 `orderId` 중복 제거가 없다(`:86-87`). 같은 주문이 두 목록에 나오면 체결이 이중 집계될 수 있음(실계좌 미확인). 또 OPEN을 날짜 필터로 호출(`from/to`는 `orderedAt`)해 전날 접수·당일 체결 대기분을 `D-1`로 잡는 우회가 필요 |
| 잔고 요약 getPresentBalance | `GET /api/v1/holdings` + `GET /api/v1/buying-power?currency=USD` + `...=KRW` + `GET /api/v1/exchange-rate` 4건을 가상 스레드 병렬 `T/TossHoldingsApi.java:79-121` | holdings `items[].quantity`(문자열, **미국은 소수 가능**), `averagePurchasePrice`, `lastPrice`. buying-power `cashBuyingPower`(현금 기반, 미수 제외). 그룹 한도 ASSET 5·ORDER_INFO 6(개장 직후 3)·MARKET_INFO 3 TPS | △ | — | **✘ 의심**: `Integer.parseInt(h.quantity())`(`:108`)는 `"1.5"` 같은 소수 수량에서 `NumberFormatException`으로 전체 대시보드 조회가 실패한다. 소수 수량은 앱·`orderAmount` 매수로 생길 수 있고 스펙이 명시적으로 허용함(미실측). `parseIntOrZero`(sellable·체결 경로)는 소수 입력 시 0 처리라 조용히 틀림 |
| 매수 가능 USD | `GET /api/v1/buying-power?currency=USD` `:51,124` | `cashBuyingPower` | ✔ | — | — |
| 판매 가능 수량 | `GET /api/v1/holdings`(필터 없음)에서 `symbol` 일치 항목 `T/TossHoldingsApi.java:171-183` | holdings는 **`symbol` 쿼리로 종목 필터 지원**("제공 시 요약도 해당 종목 기준 재계산"). 별도 `GET /api/v1/sellable-quantity`는 미체결 매도 차감 순수량(실측) | ✔(보유수량 사용은 포트 계약) | `holdings?symbol=SOXL`: 응답 크기·ASSET 한도 소모 감소 | 전체 목록을 받아 클라이언트 필터 |
| 현재가 | `GET /api/v1/prices?symbols=A,B`(**관리자 토큰**, 최대 200) `T/TossPriceApi.java:69-74` | `result[]{symbol, timestamp(null 가능), lastPrice, currency}`. MARKET_DATA 15 TPS | ✔ | — | 응답 중 `symbol, lastPrice`만 파싱 ✔. `timestamp`가 null(체결 미발생)인 종목의 가격 신뢰 규칙 없음(? 정규장 개시 전 `lastPrice` 의미 미확인) |
| 전일 종가 | `GET /api/v1/candles?symbol&interval=1d&count=1&before=…`(관리자 토큰) 종목별 1회 `T/TossPriceApi.java:152-171`, `TossCandleApi.java:114` | 일봉 `timestamp`=거래일 현지 자정, `adjusted` 기본 true, `before` inclusive, 최신순. MARKET_DATA_CHART 20 TPS. **`prices`에는 전일종가 필드가 없음**(`prices`는 4필드) | ✔ | `GET /api/v1/market-calendar/US`(정규장 `startTime/endTime` KST, 휴장일 시 4세션 null, 전일/당일/익일 3영업일 반환): 현재 코드가 개장·마감·프리마켓 시각을 **DST 표로 직접 복제**(`TossPriceApi.java:170-206` `marketOpenTime` 등)해 "정규장 진행 중이면 진행 봉 배제" 판정 → 휴장·조기마감(half-day)은 모르고, 스펙 캘린더로 대체하면 하드코딩 표 삭제 가능 | `adjusted`를 보내지 않아 기본값 true(수정주가)로 조회됨 — `getCandleBefore`·확정 종가 경로(`:128`은 DCA 경로만 `adjusted=true`를 명시)가 "미적용"을 의도했는지 문서와 코드가 불명확. 코드는 기본값에 의존 |
| 확정 종가 | `GET /api/v1/candles`(`interval=1d`, 날짜 범위 페이징) `TossPriceApi.java:219-236`, `TossCandleApi.java:50-70` | `nextBefore`(마지막 페이지 null)를 그대로 다음 `before`에 전달하도록 설계 | △ | `nextBefore`: 현재 코드는 직전 페이지 가장 오래된 봉 날짜 00:00 UTC로 `before`를 자체 계산(`TossCandleApi.java:67,108`)하며 `nextBefore` 필드는 어디에서도 읽지 않는다(grep 0건) | 스펙은 `before` **inclusive**인데 자체 계산 + `isBefore` 필터에 의존. `nextBefore`를 쓰면 날짜 연산·중복 필터가 필요 없음 |
| 환율 | `GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW`(관리자 토큰, 60s 캐시) `T/TossHoldingsApi.java:144-165` | `rate`(매수 환율), `midRate`(매매기준율), 갱신 1분, `validFrom/validUntil`, MARKET_INFO 3 TPS | ✔ | — | 응답 `result==null`이면 `rate=0`을 반환(`:158-159`)하는 방어 — 스펙은 `result` 필수라 불필요하고, 0 환율은 하류 계산을 오염시킬 수 있음(소비처가 0을 가드하는지 별도 확인 필요) |
| 캔들 UI/DCA | `GET /api/v1/candles`(1m·1d) `TossCandleApi.java` | `interval` 1m·1d만, 최대 200봉/요청, `adjusted` | ✔ | — | UI 일봉 최대 600봉(`MAX_LATEST_DAILY_COUNT`)은 3페이지 이상 호출(스펙 한도 20 TPS 내) |
| 계좌 검증 | `POST /oauth2/token` → `GET /api/v1/accounts` 첫 `accountSeq` `T/TossAuthApi.java:83-142` | ACCOUNT 그룹 **1 TPS**, 종합매매 계좌만 노출, `accountSeq`가 `X-Tossinvest-Account` 헤더값 | ✔ | — | 계좌 여러 개일 때 `getFirst()`로 선택(스펙: 현재 BROKERAGE만 노출이라 통상 1개, 그러나 다중이면 사용자 선택 수단 없음) |

### 3-2. 인증·토큰 규칙 (Redis 분산 토큰 약 690줄의 정당성)

스펙 원문(`POST /oauth2/token` 설명):
- "refresh token 은 제공되지 않습니다. 만료 시 동일 엔드포인트로 재발급합니다."
- "**client 당 유효한 access token 은 1 개입니다. 재발급 시 이전에 발급된 token 은 즉시 무효화됩니다.**"
- 만료 401 코드 3종: `invalid-token`, `expired-token`, **`token-revoked`**("새로 발급된 토큰으로 대체되어 더 이상 유효하지 않은 토큰입니다. 최신 토큰으로 다시 시도").
- 발급 AUTH 그룹 5 TPS, 토큰 수명은 스펙에 없고 응답 예시 `expires_in: 86400`(개요 문서도 수명 미기재). 허용 IP 목록 밖이면 403 `ip-not-allowed`(WTS 설정에서 등록).
- 발급 횟수 제한은 "5 TPS" 외에 명시 없음.

| 코드가 하는 일 | 스펙 근거 | 평가 |
|---|---|---|
| client별 단일 발급자 조정(lease)·동시 발급 방지 | 클라이언트당 토큰 1개이고 재발급이 이전 토큰을 즉시 무효화 → 동시 발급 시 서로의 토큰을 죽임 | ✔ **프로세스 내 직렬화는 문서로 정당화됨**(client 키별 락 1개) |
| 인스턴스 간 Redis lease·fencing generation·CAS·SHA-256 최근 발급 지문(2초) | 스펙은 "여러 인스턴스" 시나리오를 규정하지 않음. KISTA는 `kista-trading` 단일 인스턴스·stop-first 교체(`constraints.md`)라 겹침 기동이 없음 | ✘ **문서로 정당화되지 않음.** 정당화 가능한 유일한 근거는 다중 인스턴스 또는 배포 겹침인데 둘 다 운영 규칙이 금지. 토스 관리자 키를 로컬 개발과 공유하면 서로 무효화하나 Redis도 환경을 넘어서는 해결하지 못함 |
| 401마다 코디네이터 재조회, 300→600→900ms 백오프 3회, 같은 토큰 재거절 시 `forceReissue` | 스펙은 `token-revoked`를 별도 코드로 구분("최신 토큰으로 다시 시도")하지만 **전파 지연**(재발급 직후 일부 서버가 구 토큰 판정) 언급은 없음 → 2026-08-11 사례는 실측 | △ 응답 본문 `error.code`를 읽어 `token-revoked`(최신 토큰 재조회)와 `expired-token`·`invalid-token`(재발급)을 가르는 것이 문서 기반 설계. 현재는 모든 401을 한 경로로 처리 |
| `expires_in`보다 5분 일찍 만료 처리 | 수명은 응답 값(예시 86400) | ✔ 합리적 |
| `AccountTokenEvictionPort`(키 교체 시 canonical 삭제+generation INCR) | 키 교체하면 이전 키의 토큰은 새 키로 발급돼도 별개 client → 이전 토큰은 단순히 쓸모없어짐 | △ 과설계 가능(키 교체 후 `getToken`이 새 키로 발급하면 끝) |
| `verifyCredentials`/`verifyAccount`가 "어느 계좌 토큰도 건드리지 않는다" | **✘ 스펙과 모순**: 같은 client_id로 발급하면 기존 토큰이 즉시 무효화됨. 이미 등록된 키를 재검증하면 운영 중 토큰이 `token-revoked`가 되어 401 복구 경로를 탄다(`T/TossAuthApi.java:83-95`) | 등록·교체 시점에만 호출되어 영향 작으나 주석(`BrokerConnectionTestPort`)이 부정확 |

요약 판정: 스펙이 정당화하는 것은 "client(키)별 토큰 1개 + 발급 직렬화 + `token-revoked` 시 최신 토큰 재조회"까지이며, **분산 락·fencing·지문 TTL은 근거 없음(단일 인스턴스 가정에서)**. 대체안: client별 `ReentrantLock` + 메모리 캐시(+선택적 DB/Redis 영속) + 401 시 1회 재발급. 재기동 시 재발급은 이전 인스턴스가 이미 중지(stop-first)이므로 해가 없다.

### 3-3. 호출 한도 (스펙)

per client × group TPS(개요 문서): AUTH 5, ACCOUNT 1, ASSET 5, ORDER 10, ORDER_HISTORY 5, ORDER_INFO 6(09:00~09:10 KST 3), MARKET_INFO 3, MARKET_DATA 15, MARKET_DATA_CHART 20. 429 응답 `code: rate-limit-exceeded`(또는 엣지 `edge-rate-limit-exceeded`), **헤더 `X-RateLimit-Limit/Remaining/Reset`, `Retry-After`**. 일부 의심 IP 대역은 1 TPS로 제한.

| 코드 | 평가 |
|---|---|
| 클라이언트 측 호출 게이트 없음(메리츠 `CallIntervalGate`와 대비) | 키(client)·그룹별 버킷이 서버에 있고 `Retry-After`를 주므로 게이트 대신 `Retry-After` 기반 재시도가 문서 기반 설계 |
| 429: 관리자 시세 GET만 500ms·1s 백오프 2회, 계좌 API(주문 포함)는 재시도 없이 `BrokerRateLimitException` | `Retry-After`(초)를 무시하고 고정 백오프. 주문 POST 재시도 금지는 멱등키가 있어 사실상 안전(`clientOrderId`로 재전송 가능)함에도 보수적 — 변경은 선택 사항 |
| 관리자 토큰으로 모든 시세 호출 | 관리자 client 한 개의 MARKET_DATA 15 TPS·CHART 20 TPS를 모든 계좌 배치가 공유 → 마감 시 종목 수만큼 캔들 호출(종목당 1~2회)이 몰려도 한도 내 |

### 3-4. 주문 유형·시간·멱등성

| 항목 | 스펙 | 평가 |
|---|---|---|
| 유형 | LIMIT·MARKET(미국), `timeInForce` DAY·CLS(미국 LIMIT만, = LOC)·OPG(국내). **LOC는 지원, MOC·MOO·미국 시가단일가는 없음** | KISTA 우회 (MOC → LIMIT@0.01+CLS) 타당, (MOO sell → LIMIT@0.01+DAY) 대신 MARKET+DAY 가능 |
| 가격 제한 | 상·하한 밖 422 `price-out-of-range`; 1억원 초과 `confirmHighValueOrder=true` 필요(`confirm-high-value-required`); 최대 30억원(`max-order-amount-exceeded`) | KISTA 주문 금액 규모에선 해당 없음. 단 큰 계좌의 대형 주문은 `confirmHighValueOrder` 누락 시 400 |
| 가능 시간 | 목록 없음. 외 시간 422 `order-hours-closed`. 소수·금액 주문은 정규장 시작~종료 1시간 전. 세션은 `GET /api/v1/market-calendar/US`(day·pre·regular·after, KST) | KISTA는 정규장 안에서 접수 |
| 멱등성 | `clientOrderId` 36자, 10분 유효, 같은 키·같은 내용 → 원 결과 재반환, 처리 중 409 `request-in-progress`, 다른 내용 422 `idempotency-key-conflict` | ✔ 코드·`toss-api.md` 정합. `Order` 응답에 `clientOrderId`가 없어 사후 조회 불가(스펙과 일치) |
| 조회 가능 범위 | 목록·상세는 Open API로 접수 가능한 유형(지정가·시장가·장마감지정가)만 반환 | KISTA 주문과 일치 |
| 정정 | `POST /orders/{id}/modify`(새 orderId 발급) | 불필요 |
| 오류 응답 형식 | `{"error":{"requestId","code","message"}}` + 헤더 `X-Request-Id`. 토큰 엔드포인트만 OAuth 표준 `{error,error_description}` | 코드는 `error.code`만 사용 ✔ (`requestId`는 로그에 남기면 CS 문의에 유용 — 현재 본문 전체를 예외 메시지에 포함) |

### 3-5. 토스의 공급자 역할

| 역할 | 사용 API·토큰 | 문서상 제약 |
|---|---|---|
| 시세·캔들·환율·장 정보 | **관리자(별도) client 토큰** — "계좌 정보 없이 액세스 토큰만으로 호출"(Market Data·Market Info 그룹). `X-Tossinvest-Account` 헤더 불필요 | MARKET_DATA 15, CHART 20, MARKET_INFO 3 TPS(per client). 캔들은 `1m`·`1d`만, ≤200/요청, 분봉은 `before`가 날짜 단위인 코드 쪽 한계로 1페이지 |
| 보유·매수가능·체결 | **계좌별 사용자 client 토큰** + `X-Tossinvest-Account` | ASSET 5, ORDER_INFO 6, ORDER_HISTORY 5, ORDER 10 TPS(per 사용자 client) |
| 대시보드(보유 현황) | `holdings`, `buying-power`, `exchange-rate` | holdings가 종목 필터·요약(원금·평가·손익)을 이미 제공 |
