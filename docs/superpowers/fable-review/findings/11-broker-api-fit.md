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

## 8. 토스 관리자 키 공용 시장 데이터의 메리츠 대체 가능성

전제(코드로 확인): 토스에서 공용으로 받는 것은 `TossPriceApi`(현재가·전일종가·확정종가), `TossCandleApi`(일봉·분봉), `exchange-rate`, 그리고 이를 소비하는 `TossAdminPriceSource`(관리자 시세 1순위, `AdminMarketPriceAdapter`가 Toss → KIS 순으로 누락 종목만 보충), Mock 브로커(`MockBrokerAdapter.java:29,87,140`가 `TossPriceApi.getClosingPrice/fetchConfirmedCandle`을 직접 호출), root `api.market`(`CandleQueryHttpAdapter` → 내부 `/api/internal/broker/candles/{latest,range}`)·`api.benchmark`(`CurrentExchangeRateHttpAdapter` → `/api/internal/trading/stats/exchange-rate`). **장 상태·휴장일은 토스에서 받지 않는다** — 휴장일은 `marketcalendar`의 Alpaca 어댑터, 정규장 진행 판정은 `TossPriceApi.resolveMarketSessionAt`의 하드코딩 DST 표(`:170-206`)다.

### 8-1. 항목별 판정

| 항목 | 현재 토스 (엔드포인트 / 인증 주체 / 한도) | 메리츠 스펙 대응 (경로·파라미터·응답 필드·범위) | 메리츠 인증 | 호출 한도 | 판정 |
|---|---|---|---|---|---|
| 현재가 | `GET /api/v1/prices?symbols=` 최대 200종목 1회 / **관리자 키** / MARKET_DATA 15 TPS | `GET /market/v1/overseas/prices` **단건**(`mrkt_div_code=OV, mrkt_cls_code(OQ/NY/AX), iscd, dely_rltm_cls_code 0 실시간·1 지연`) → `prpr`. 종목 범위: 해외 거래소별 코드 필수(KISTA 레지스트리 6종목 고정, 신규 종목마다 거래소 코드 매핑 필요 — 토스는 심볼만). 시간외: 정규장 `prices`와 별도로 `prices/daytime`(주간)·`prices/after`(애프터)가 있음. **`prices`가 프리·애프터 체결을 포함하는지 스펙에 없음**(? 토스 `lastPrice`는 확장시간 체결가 포함이라는 `AdminMarketPricePort` 계약과 의미가 다를 수 있음) | 앱키 1개=계좌 1개, 토큰 필수(`mac_address` 포함). **공용·계좌 무관 키 없음** | 앱키 단위, 스펙 수치 없음(`EGW00200`). 코드는 앱키별 110ms 게이트 | **부분 가능** — 정규장 현재가는 가능(이미 계좌 단계에서 사용 중), 종목당 1회×110ms, 시간외 가격 의미는 실측 필요 |
| 전일 종가 | 캔들 `GET /api/v1/candles?interval=1d&count=1&before=` 종목당 1회 + 세션 판정·캐시 / 관리자 키 / CHART 20 TPS | **`prices` 응답의 `prdy_clpr`**(전일종가) — 현재가와 **같은 호출 1회**로 동시 취득. 메리츠 계좌 단계 `MeritzPriceApi.getPriceSnapshot`이 이미 사용(`:46-60`). 정규장 종료 후·프리마켓 시간대에 `prdy_clpr`이 "직전 거래일 종가"로 유지되는지는 스펙에 없음(? 토스 코드가 세션 판정 로직을 둔 이유가 바로 이 경계) | 위와 같음 | 위와 같음 | **대체 가능(조건부)** — 호출·코드가 크게 줄지만 경계 시각(05:00~06:00·18:00~) 동작을 실측 후 확정 |
| 확정 종가(마감 리포트·VR 롤오버·Mock) | 일봉 캔들 `from=to` / 관리자 키 | `GET /market/v1/overseas/candles/days`(`from=to=US거래일`, `mod_stpr_cls_code` 필수) → `date, prpr(종가), oprc, hprc, lprc, acml_vol`. 이미 `MeritzPriceApi.fetchConfirmedClose`(`:98-129`)가 사용 | 위와 같음 | 위와 같음 | **대체 가능** — `1`(원 주가) 사용 유지. 휴장·미발행 봉은 날짜 불일치로 탈락 |
| 일봉 캔들(기간·개수) — UI·DCA 지표 | `GET /api/v1/candles` 200봉/요청, `nextBefore` 페이징, `adjusted=true`(분할·배당 조정, DCA만) / 관리자 키 / CHART 20 TPS. UI 최신 600봉, 기간 최대 5년 | `candles/days`: `from`·`to` 함께 주면 "그 기간 일봉 모두", 생략 시 **최신 40건**, **개수 파라미터 없음·연속조회 없음**. 최근 일자 앞. `mod_stpr_cls_code` `0`=**분할** 보정, `1`=원 주가(**배당 조정 언급 없음**). 분봉: `candles/minutes`(`hour_cls_code` 초 단위 간격, 최근 구간부터, `korea_date/korea_hour`) | 위와 같음 | 위와 같음 | **부분 가능** — 5년(≈1,260봉)·600봉을 1회로 줄 수 있는지 문서에 없음(? 서버 상한 불명, 분할 호출하려면 구간 쪼개기), 배당 조정 차이가 DCA 지표값을 바꿀 수 있음(레버리지 ETF 소액 배당, 지표 민감도는 별도 검증). UI 차트용은 가능성 높음, DCA 지표는 `0`이 `adjusted=true`와 동치인지 확인 전엔 유지 |
| 환율(USD/KRW) | `GET /api/v1/exchange-rate` `rate`(매수), `midRate` / 관리자 키 / MARKET_INFO 3 TPS, 60s 캐시 | ① `GET /forex/v1/rates?stnd_date=YYYYMMDD&crcd=USD`(고시환율: `deal_stnd_exrt` 매매기준환율, `stnd_exrt`, `tltr_slrt/byrt`, `tr_date`) ② `GET /market/v1/overseas/fx/prices?iscd=USDKRWSMBS`(시장 환율 `prpr`, 전일대비, 시고저) | 위와 같음 | 위와 같음 | **대체 가능** — `midRate` 소비처(벤치마크)는 `deal_stnd_exrt` 또는 `fx/prices.prpr`, 대시보드 KRW 환산에 쓰는 `rate`(토스 "매수 환율")는 `tltr_aply_slrt`(전신환 적용 매도율, 고객이 달러를 살 때) 계열로 의미를 맞춰야 하며 값이 소폭 다름. 응답 라벨 `"TOSS_INVEST"`(`BenchmarkService.java:240`) 변경 필요 |
| 장 상태·세션 판정 | 토스 API 미사용(코드 내 DST 표) | `GET /reference/v1/overseas/market-hours`(`ovrs_stck_mrkt_tr_cls_code=01` → `ovrs_mkop_cls_code`(004 정규장 개시 등), `mrkt_begn_time/mrkt_fin_time` KST HHMM, 닫힘이면 `0000`), `GET .../market-days`(해당 거래소 현재 영업일 `bsop_date`) | 위와 같음 | 위와 같음 | **가능(현재 상태 단건)** — 정규장 진행 여부 판정용 DST 표 대체 가능. 세션 목록·조기마감 규칙은 스펙에 없음 |
| 휴장일 캘린더 | 토스 API 미사용(Alpaca 어댑터) | **없음**(영업일 단건뿐, 휴장일 목록·다음 영업일 계산 API 없음) | — | — | **불가** — Alpaca 유지(토스 `market-calendar/US`는 전일·당일·익일 3영업일과 4세션을 주지만 현재 미사용) |
| 관리자 시세 체인 `TossAdminPriceSource` | Toss → KIS 순(`@Order(1)`). 현재가·전일종가·확정종가 부분 결과 계약 | 위 세 항목의 메리츠 구현을 `MeritzAdminPriceSource`로 교체(체인 구조 `AdminMarketPriceAdapter`·`AdminPriceSource` 유지) | **관리자용 메리츠 앱키 필요** | 관리자 앱키 전용 게이트 | **대체 가능(조건부)** — KIS 삭제 확정이면 체인 단계가 1개가 되어 `AdminMarketPriceAdapter`·`AdminPriceSource` 추상도 불필요해질 수 있음(별도 판단) |
| Mock 브로커 시세 | `TossPriceApi.getClosingPrice/fetchConfirmedCandle`(관리자 키) → 체결 판정에 OHLC 사용 | `candles/days`의 `oprc/hprc/lprc/prpr` | 위와 같음 | 위와 같음 | **대체 가능** — 단 Mock이 `TossPriceApi`에 직접 의존하는 구조(`MockBrokerAdapter.java:4`)를 포트로 바꿔야 함 |
| 캔들 통계(`TossCandleApi`) | `CandlePort`(UI 최신·기간, DCA 일봉) | 위 일봉 행과 동일 | 위와 같음 | 위와 같음 | 일봉 행과 동일(부분 가능) |
| root `api.market` 캔들·`api.benchmark` 환율 | 내부 HTTP로 trading-core 경유 → 토스 | 내부 계약(`/api/internal/broker/candles/*`, `/stats/exchange-rate`)은 공급자 무관한 `shared.contract` 타입이므로 **root 코드 변경 불필요**. DTO 이름 `TossCandleResponse`·`CurrentExchangeRate.source="TOSS_INVEST"`만 정리 | — | — | 공급자 교체와 무관하게 root 영향 작음 |

### 8-2. 메리츠 인증 조건(핵심 제약)

- 메리츠 토큰은 **앱키 1개 = 계좌 1개**에 매핑("접근토큰에 매핑된 계좌" 문구가 계좌·시세 API 전반에 반복). 스펙에 **계좌와 무관한 공용 키는 없다.** 시세 API(`/market/v1/...`)도 `authorization: Bearer`·`mac_address`가 필수라 토큰 없이 부를 수 없다(토스는 "계좌 정보 없이 액세스 토큰만" 호출 가능, 계좌 헤더 불필요).
- 따라서 서버 공용 호출에는 **운영자 본인의 메리츠 계좌 앱키(관리자 키)가 필요**하다. 사용자 키로 부르면 ① 사용자 한도·게이트를 시세 호출이 갉아먹고 ② 사용자 키 만료·권한 오류가 시세 장애로 번지며 ③ 사용자별 계좌 유무에 시세가 종속된다 → 권장하지 않음.
- 관리자 키 토큰 관리: 메리츠 재발급은 같은 토큰·같은 `expires_date`를 돌려줘(실측) 비파괴이므로 분산 조정이 필요 없다. 다만 현재 `DbTokenCoordinator`는 `accountId`(UUID) 키라 계좌 없는 관리자 키에는 맞지 않아 앱키 단위 메모리 캐시(락 1개) 한 곳이 필요하다.
- 접속 IP 제한 여부, 실시간 시세 이용 조건·신청 필요 여부는 스펙에 없음(? 확인 필요).

### 8-3. 호출 한도 공유

- `CallIntervalGate`는 **앱키 문자열 단위**(`MeritzHttpClient.awaitSlot` → `account.appKey()`)라 관리자 앱키와 사용자 앱키가 다르면 버킷이 분리된다. 단 운영자 계좌를 매매에도 쓰고 같은 키를 관리자 키로 쓰면 시세와 주문이 같은 110ms 게이트를 공유한다(시세 N종목 호출이 주문 접수를 최대 N×110ms 지연, 최대 대기 20s 상한).
- 스펙은 한도 수치를 주지 않는다(`EGW00200`만). 문서의 "API별 10 TPS"는 실측 추정 → 시세 호출이 `GET`이라 `EGW00200` 시 백오프 재시도(최대 3s)가 걸린다.
- 토스는 한도가 **client×그룹**(MARKET_DATA 15·CHART 20·MARKET_INFO 3 TPS)이고 관리자 client 1개가 모든 계좌 배치를 받는 구조라 대체 시 메리츠 단일 관리자 앱키가 같은 부하를 110ms 게이트로 직렬화하게 된다(종목 6개 기준 현재가+전일종가 약 0.7s, 확정종가 약 0.7s, 충분).

### 8-4. 결론 후보

| 후보 | 내용 | 근거 | 위험 |
|---|---|---|---|
| A. 전부 대체 | 시세·캔들·환율을 메리츠로 통일, 토스는 주문(+계좌별 보유 조회) 전용 | 전일종가가 `prices` 1회로 해결, 토스 관리자 토큰·Redis 조정·DST 표·세션 캐시 제거 | 5년/600봉 범위·배당 조정·시간외 가격 의미·휴장일 목록 부재(Alpaca는 유지) 4가지가 **미확인**. 관리자 앱키 장애가 곧 시세 전체 장애(토스 폴백 소멸) |
| **B. 일부만 대체(권장 후보)** | 현재가·전일종가·확정종가·Mock 시세·환율은 메리츠, **일봉 캔들(UI·DCA)은 토스 유지** | 앞의 항목은 이미 계좌 단계에서 메리츠로 운영 중이라 실측 근거가 있고, 캔들은 메리츠 범위 상한·배당 조정을 모름. 캔들만 남기면 토스 관리자 토큰(`AdminToken`)이 남아 제거 효과가 반감 | 토스 관리자 키 의존이 남음 |
| C. 유지 | 토스 관리자 키 유지 | 시세 장애 시 메리츠 한 곳이 단일 실패점이 되지 않음 | 두 공급자·두 토큰 모델·Redis 조정 유지 비용 지속 |

권장 순서: ① 운영 서버에서 메리츠 읽기 전용 실측 3건(관리자 앱키로 `prices`의 `prdy_clpr`가 05:00·06:00 KST 경계에서 직전 정규 종가인지, `candles/days`를 1,260봉 구간 1회 호출 시 전량 반환인지, `prices`가 시간외 체결가를 포함하는지) → ② 통과하면 B → ③ 캔들까지 통과하면 A.

### 8-5. 대체 시 삭제 가능한 토스 코드와 남는 의존

| 대상 | 줄 수 | 비고 |
|---|---|---|
| `TossPriceApi` | 255 | 현재가·전일종가·세션 DST 표·캐시 (A·B 모두 삭제) |
| `TossAdminPriceSource` | 59 | 체인 교체 |
| `UsdKrwRateCache` + `TossExchangeRate` | 46 + 16 | 환율을 메리츠로 옮길 때(B 포함) |
| `TossHoldingsApi`의 환율 부분(`:37-42,137-165`) | 약 35 | 대시보드 환율은 메리츠 환율로 |
| `TossCandleApi` + `TossCandle` | 174 + 21 | **A에서만 삭제**(B는 유지) |
| 관리자 토큰 경로: `TossAuthApi`(`getAdminToken`·`recoverAdminToken`·`issueAdminToken`·`@Value admin*`) ~15, `TossDistributedTokenCoordinator.getAdminToken/recoverAdminToken/ADMIN_SCOPE` ~10, `TossHttpClient.getCommon·retryOnTooManyRequests`(`:54-83`) ~30, 설정 `toss.admin-client-*` | 약 55 | **A에서만 삭제.** B에서는 캔들 때문에 남음 |
| `TossBrokerAdapter`의 가격 6메서드 위임(`:67-110`) | 약 45 | 가격 포트는 라우터의 관리자 체인에 맡기고 토스 어댑터는 계좌 계열만 남기도록 정리 |

합계: **B ≈ 395줄, A ≈ 685줄**(테스트 제외). 남는 토스 의존: 주문(`TossOrderApi` 214줄)·계좌 보유/매수가능(`TossHoldingsApi` 약 180줄)·계좌별 토큰 코디네이터·계좌 검증, 그리고(B 한정) 일봉 캔들용 관리자 토큰.

## 4. 두 브로커 공통점·차이점

| 축 | 메리츠 | 토스 | 공통 골격에 넣을 수 있나 |
|---|---|---|---|
| 인증 | OAuth2 client_credentials(form), 앱키=계좌 종속, 헤더 `authorization`+필수 `mac_address`. 재발급은 같은 토큰(실측) | OAuth2 client_credentials(form), **client당 토큰 1개·재발급 시 이전 토큰 무효화**, 헤더 `Authorization`+계좌 헤더 `X-Tossinvest-Account` | 요청 서명자(헤더 주입)는 벤더별. "토큰 확보·1회 재발급" 골격은 공통 가능, 토스는 직렬화 필요 |
| 성공 판정 | **본문 `rsp_cd`**(HTTP 500도 성공 가능), API별 성공 코드가 다름 | HTTP 상태코드 + `result` 래퍼 | 불가 — 오류 매퍼는 벤더별 |
| 오류 형식 | `{rsp_cd, rsp_msg, rsp_sub_msg}`, 오류 코드표 없음 | `{"error":{requestId, code, message}}` 코드표 있음, 토큰 엔드포인트만 OAuth 표준 | 불가 |
| rate limit 신호 | 본문 `EGW00200`(HTTP 500), 수치·헤더 없음 | HTTP 429 + `X-RateLimit-*`·`Retry-After`, client×그룹 TPS 공개 | 재시도 가능 판정 인터페이스(`isRateLimited`, `retryAfter`)만 공통 |
| 호출 간격 | 클라이언트 게이트 110ms(앱키별) | 게이트 없음(서버 버킷) | 최소 호출 간격은 정책 값(메리츠 110, 토스 0/없음)으로 공통화 가능 |
| 페이징 | 30건/페이지, `tr_cont=1`+`tr_cont_key`(쿼리, 공백 포함 원문), 키 오류 시 조용히 첫 페이지 | CLOSED 목록 `cursor`/`hasNext`(limit ≤100), 캔들 `before`/`nextBefore`(≤200) | 불가 — 방식이 다름 |
| 주문 식별자 | 숫자 `oder_no`(+ 날짜 `oder_date`가 취소에 필요) | 불투명 문자열 `orderId`, 취소·정정마다 새 orderId | `externalOrderId` 문자열로 이미 통일 |
| 멱등성 | 없음 | `clientOrderId` 36자·10분 | 포트에 `clientOrderId`(이미 있음)를 두되 메리츠는 무시 |
| 금액·수량 | 문자열/숫자 혼재, 수량 소수 10자리, 가격 4자리, 요청 Number 타입 | 문자열 decimal, 미국 소수 수량은 시장가 매도만, 가격 $1 이상 2자리 | 파싱 헬퍼(`decimal`, `wholeQuantity`)는 공용 유틸로 합칠 수 있음 |
| 날짜·시간대 | 주문일·체결일은 미국 거래일(YYYYMMDD) | 주문 접수일 KST, 체결 시각 ISO8601 KST | 불가(변환 규칙이 다름) |
| 시세 호출 | 종목 단건(현재가·일봉 모두), 거래소 코드 필수 | 현재가 다건(≤200), 캔들 단건 | 불가 |

`BrokerHttpPolicy`(baseUrl·최소 간격·재시도 가능 판정·오류 매퍼·서명자)로 묶을 수 있는 것은 **baseUrl·타임아웃(3s 연결/10s 읽기 이미 `HttpClientTimeouts.jdkTimeouts` 공용)·최소 호출 간격·`isRetryable(응답)` 함수 자리·헤더 서명자 함수**까지다. 응답 판정·페이징·토큰 규칙은 벤더별로 남아야 한다(`MeritzResponse`의 본문 판정과 `TossHttpClient`의 401/429 루프는 구조가 달라 추상화하면 오히려 분기만 늘어난다).

## 5. 현재 코드에서 줄일 수 있는 것 / 빠진 처리

### 5-1. 미사용·중복 (삭제 후보)

| 항목 | 근거 | 위치 |
|---|---|---|
| `MarginPort.getMargin`·`MarginItem`·메리츠 `EstimateRow.rate`·토스 `getMargin`(3병렬 호출) | 호출처 0 | `MeritzTradingApi.java:75-81`, `TossHoldingsApi.java:55-77` |
| `BrokerPricePort.getPriceSnapshot(s)`·`PriceSnapshot`(타입 사용처 재확인 필요) | 호출처 0(테스트 `never()`만) | 양 어댑터 + 라우터 |
| 메리츠 `SUCCESS_CODES`의 `5620`·`5822` | 스펙상 국내 예약주문·국내 조회 전용, 해외 경로 무관 | `MeritzResponse.java:27` |
| 메리츠 `OrderRow` 9필드 합집합(당일·기간 2엔드포인트 공용) | 당일 `odqt/cntg_qty/oder_able_qty`, 기간 `oder_dqty/cntg_dqty/ncnt_odqt` — 기간 경로는 `oder_dqty-cntg_dqty` 계산으로 `ncnt_odqt` 제거 가능 | `MeritzOrderApi.java:52-61` |
| 메리츠 주문 body `Map<String,String>` | 스펙 타입이 `odqt`·`orgl_oder_no` Number, `oder_cond_cls_code` 선택 → 작은 record 2개(주문·취소)로 대체하면 매핑 배열 `m[0..2]` 제거 | `MeritzOrderApi.java:74-87` |
| 토스 `getPresentBalance`의 USD·KRW 매수가능 2회 + 환율 | 대시보드용이면 KRW 호출은 ORDER_INFO 한도(개장 직후 3 TPS)를 쓰므로 환산 표시 필요 시에만 | `TossHoldingsApi.java:89-97` |
| 토스 `getSellableQuantity` 전체 holdings | `holdings?symbol=` 필터 지원 | `TossHoldingsApi.java:171-183` |
| 토스 `nextBefore` 미사용 + 자체 `before` 날짜 계산 | 스펙이 `nextBefore`를 제공 | `TossCandleApi.java:50-70,108` |
| 토스 DST 세션 표 복제 | `market-calendar/US` 또는 메리츠 `market-hours`로 대체 | `TossPriceApi.java:170-206` |
| 토스 환율 `result==null → 0` 폴백 | 스펙상 `result` 필수 | `TossHoldingsApi.java:158-159` |
| 종목별 `getExecutions` 반복(메리츠: 같은 계좌·기간 history를 종목마다 전량 조회) | 스펙에 종목 필터 없음 → 호출부에서 계좌·일자당 1회로 합치면 페이지 호출이 종목 수만큼 감소 | `T/TradingReporter.java:58`, `CancelledOrderFillRecorder.java:38` |
| 토스 분산 토큰(Redis 약 690줄) | 3-2절: 단일 인스턴스에서 문서 정당화 불가 | `TossDistributedTokenCoordinator`(207)·`TossRedisTokenStore`(229)·`TossTokenStore`(39)·관련 |

### 5-2. 문서가 보장하지 않아 방어를 유지해야 하는 것

- 메리츠 `HTTP 500 + rsp_cd` 본문 판정, `0000`이 오류에도 오는 경우(스펙이 market-hours에서 명시), 연속조회 키 오류 시 조용히 첫 페이지 → `MAX_PAGES` 가드 유지, `iscd` 접미사 시 가격 0 → `signum<=0` 검사 유지. 모두 스펙이 함정으로 명시한 것이라 **제거하면 안 된다**.
- 토스 `result` 래퍼 언랩, 401 코드 구분은 스펙으로 보장됨.

### 5-3. 문서상 필요한데 빠진 처리 (버그 의심은 [의심] 표시)

| 구분 | 내용 |
|---|---|
| [의심] 메리츠 | 기간 폴백의 `ncnt_odqt` 신뢰(스펙은 계산하라고 명시) — `MeritzOrderApi.java:61,163` |
| [의심] 메리츠 | holdings `rmnd_clcd`(일반·소수·대출) 행 미합산, 소수부 버림 — `MeritzTradingApi.java:92,138` |
| [의심] 토스 | `Integer.parseInt(quantity)`가 소수 수량에서 대시보드 전체 실패 — `TossHoldingsApi.java:108` |
| [의심] 토스 | `status=OPEN`/`CLOSED` 목록에서 `PARTIAL_FILLED`가 양쪽에 있어 `orderId` 중복 제거 없음 — `TossOrderApi.java:86-87` |
| 토스 | 429의 `Retry-After` 무시, 계좌 API 429 무재시도(주문 POST는 멱등키로 재시도 가능) |
| 토스 | 401 본문 `error.code`(`token-revoked`/`expired-token`/`invalid-token`) 미사용 |
| 토스 | 취소 409 `already-processing`·422 `cancel-restricted` 미분류 |
| 메리츠 | `EGW00202`을 REST 권한 오류로 분류하나 스펙에서는 WebSocket Bearer 접두어 누락 코드 |
| 메리츠 | 토큰 응답 `expires_date`(스펙 밖 필드)에 단독 의존, `expires_in` 폴백 없음 |
| 공통 | 두 브로커의 `verifyAccount`가 호출 한도·재발급 부작용(토스 토큰 무효화)을 고려하지 않음 |

## 6. 문서 드리프트

| 문서 | 서술 | 스펙/코드와 어긋남 |
|---|---|---|
| `meritz-api.md` 일봉 | `mod_stpr_cls_code` 필수만 기재, 코드 주석은 `1`=수정주가 반영 | 스펙: `0`=분할 보정, **`1`=보정 전 원주가** |
| `meritz-api.md` 호출 간격 | "초당 한도(API별 10 TPS)" | 스펙에 수치 없음 — 실측·포털 출처 표기 필요 |
| `meritz-api.md` 오류 코드 | `EGW00121/123`·`IGW50024`·`1543`·`5988`·`0760/EGW00202` 권한 | 스펙에 코드표 없음. `EGW00202`은 스펙에서 WebSocket 접두어 오류. 나머지는 실측 근거로 표기 필요 |
| `meritz-api.md` 토큰 | `expires_in` 43200·`expires_date` | 스펙 응답은 `expires_in`(예시 604800=7일)만, `expires_date` 필드 없음 |
| `meritz-api.md` 체결 조회 | history 1행·누적 체결(실측 733건) | 스펙 history도 주문당 1행이나 `detail` API는 체결 건별 행이라 혼용 금지 주의 추가 필요 |
| `toss-api.md` 시세 | "Toss는 MARKET 주문 미지원"(`TossOrderApi.java:42` 주석 포함) | 스펙: US `MARKET`·`DAY`/미국 소수 시장가 매도 지원, 미지원은 MOC·MOO |
| `toss-api.md` 토큰 | Redis 분산 fencing을 필수 설계로 기술 | 스펙 근거는 "client당 토큰 1개·재발급 즉시 무효화"까지 |
| `toss-api.md` 연결 검증 | `BrokerConnectionTestPort` "어느 계좌 토큰도 건드리지 않는다" | 같은 client_id로 발급하면 기존 토큰이 무효화됨(스펙) |
| `toss-api.md` 체결 상태 | "PARTIAL_FILLED는 OPEN에 속함" | 스펙 CLOSED 상태 집합에도 `PARTIAL_FILLED`가 기재됨 |
| `toss-api.md` 시세 | "adjusted=true는 이 경로만" | 스펙 기본값이 `true` — 다른 경로는 `adjusted`를 안 보내므로 사실상 수정주가 조회(의도 확인 필요) |
| `toss-api.md` 캔들 페이징 | 직전 페이지 가장 오래된 봉 날짜 00:00 UTC | 스펙은 `nextBefore` 제공 |
| `docs/agents/modules/tradingcore/broker.md` | 사실 확인 못 함(이번 조사 범위 외, 미열람) | — |

## 7. 불명확·확인 못 한 것

1. 메리츠 `prices`의 프리·애프터 체결가 포함 여부, `prdy_clpr`의 경계 시각(정규장 종료 직후·프리마켓) 의미.
2. 메리츠 `candles/days` 1회 호출의 최대 기간·봉 수(스펙 없음), 배당 조정 여부(`0`은 분할만 서술).
3. 메리츠 TPS 수치(스펙에 없음), 110ms 근거는 실측뿐. 실시간 시세 이용 신청·IP 제한 여부.
4. 메리츠 `orders/today`의 "당일"이 KST인지 미국 거래일인지, `estimate`의 `intg_marg_iqry_yn` 기본값, 주문 API의 소수 수량 입력 규칙, 프리·애프터 주문 가능 여부.
5. 메리츠 `tr_cont_key` 쿼리 인코딩(`+`·`&` 포함 시) — 733건 연속조회 실측은 통과.
6. 토스 `PARTIAL_FILLED` 양쪽 목록 중복 여부, `lastPrice`의 확장시간 체결가 포함 여부, `timestamp=null` 의미, 가격 절삭 규칙과 상위 반올림의 경계 영향.
7. 토스 토큰 수명 공식 문서화(응답 예시 86400만), 개요 문서 TPS 수치는 웹 요약본(`X-RateLimit-Limit` 헤더가 정본).
8. 토스 관리자 키를 로컬 개발과 공유하는지(공유하면 서로의 토큰을 무효화).
9. `docs/agents/modules/tradingcore/broker.md`·`guides/04-broker.md`는 시간상 열람하지 못함(지침서 재작성 시 대조 필요).
10. 실계좌 호출·빌드·테스트는 금지 조건이라 어떤 항목도 실호출로 검증하지 못했다.
