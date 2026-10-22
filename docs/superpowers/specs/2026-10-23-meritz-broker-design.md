# 메리츠증권 브로커 연동 설계

## 의도

사용자는 메리츠증권 계좌에서 SOXL 무한매수를 수동으로 운용 중이다(2026-10-23 기준 417주, 평단 $124.81, 매일 KST 04:30 전후 수동 주문). 이를 kista 자동 분할매매로 옮기려 한다. 메리츠를 KIS·토스와 같은 급의 미국주식 증권사(`Broker.MERITZ`)로 추가하되, 베타 API·모의계좌 부재를 고려해 **관리자 설정에서 기본 비활성**으로 배포하고 본인 계좌로 실매매 검증 후 사용자에게 연다.

## 목표 / 비목표 / 반목표

- 목표: 메리츠 계좌 등록(앱키·시크릿) → 연결 테스트 → INFINITE·VR·PRIVACY·DCA 등 미국주식 전략이 KIS와 동일하게 동작(공통 7개 capability). kista-ui 계좌 등록에서 메리츠 선택.
- 비목표(1차 제외): 통계 capability 5종(`BrokerStatisticsPort` — Toss만), 관리자 시세 소스(`AdminPriceSource`), 실시간 WebSocket, 환전, 예약주문, MOO/LOO 개장 경매 주문(`supportsOpenAuctionOrders()`는 false 유지 — 메리츠는 31/33을 지원하므로 후속으로 켤 수 있음), 키 만료 추적(`ApiKeyExpiryScheduler`).
- 반목표(동작해도 실패로 보는 것):
  - 주문 POST 자동 재시도로 이중 주문이 나는 것 — 메리츠는 멱등키(clientOrderId)가 없다.
  - HTTP 상태코드로 성공/실패를 판정하는 것 — 메리츠는 모든 업무 오류를 HTTP 500으로 준다.
  - `warn_cls_code≠0`(미접수)을 접수 성공으로 기록하는 것.
  - `oauth2/revoke` 호출 — 같은 앱키의 유일한 토큰을 즉시 끊는다. 코드에 넣지 않는다.

## 제약

- 기존 중간 진입 기능(`initialHoldings`/`initialAvgPrice`, `StrategyCreationService`)으로 진행 중 사이클을 이어받는다 — 메리츠 전용 작업 없음. 전환 시 수동 주문 중단·미체결 정리 후 전략 등록(운영 절차, 코드 아님).
- 계좌번호 없음: 앱키에 계좌 1개가 묶이고, 요청에 계좌 파라미터(`acno` 등)를 넣으면 실패한다. UPBIT와 같은 "accountNo=null, appKey가 식별자" 경로를 탄다.
- 모듈 경계·ArchUnit 규칙(architecture.md, constraints.md) 준수: 벤더 모델은 `broker.domain.model.meritz`(internal), 어댑터는 `broker.adapter.out.meritz`.
- 시간 기준: 메리츠 일봉 `date`·주문 `oder_date`·취소 요청 `oder_date`는 **미국 거래일**(실측: KST 9/2 04:30 주문의 `oder_date`=`20260901`). KST↔US 변환은 `UsTradeDates`로, 사용하는 클래스는 `HexagonalArchitectureTest` allowlist에 FQCN으로 추가.

## 실측 API 사실 (2026-10-23, 운영 서버, 조회 전용)

- Base `https://openapi.imeritz.com:9443`. 모의 서버 없음.
- 토큰: `POST /oauth2/token` form `grant_type=client_credentials&client_id&client_secret&scope=login` → `{access_token, expires_in:43200, expires_date}`. **만료 전 재발급 시 동일 토큰 반환**(멱등) — 프로세스 간 토큰 경합 없음.
- 공통 헤더: `authorization: Bearer`, `mac_address`(12자리 대문자 hex, 값 검증 없음 — 고정값 사용 가능).
- 응답 판정 순서: ① 본문 JSON 파싱(HTTP 상태 무시) ② 주문/취소면 `data.warn_cls_code` ③ `rsp_cd` ∈ {`0000`,`0001`,`5620`,`5762`,`5766`,`5820`,`5822`}면 성공 ④ 자료 유무는 `data` 비었는지로(5820이어도 data가 있을 수 있음).
- 오류(모두 HTTP 500, `EGW00103`만 403 가능): `EGW00121`/`EGW00123` 토큰 무효·만료 → 재발급 후 1회 재시도, `EGW00200` 초당 한도(API별 10 TPS), `EGW00103` 잘못된 client_id·`0760`/`EGW00202` 권한 없음 → 자격증명/권한 예외(재시도 금지), `IGW50024` mac 헤더 누락.
- 숫자 필드가 문자열/숫자 혼재(`"417.0000000000"`, `36`) — 파서는 둘 다 받는다.
- 거래소: 주문·잔고는 벤더코드 `ovrs_exch_vndr_idno`(NASDAQ `0537`, AMEX `0066`, NYSE `0321`), 시세는 `mrkt_cls_code`(`OQ`/`AX`/`NY`) + `mrkt_div_code=OV`. 실측 매핑: TQQQ=OQ/0537, SOXL·USD·MAGX·UPRO·QLD=AX/0066.
- 사용 엔드포인트:
  - 현재가 `GET /market/v1/overseas/prices`(`iscd`, `dely_rltm_cls_code=0`) → `prpr`, `prdy_clpr`
  - 일봉 `GET /market/v1/overseas/candles/days`(`mod_stpr_cls_code` 필수, `from`/`to` YYYYMMDD US) → `[{date, prpr(종가), oprc,hprc,lprc}]` — 확정 종가 조회에 사용
  - 잔고 `GET /accounts/v1/me/overseas/holdings`(연속조회) → `ovrs_stck_symb_iscd`, `rmnd_dqty`, `seln_able_dqty`, `avrg_unpr`, `psnt_prc`, `frcr_vltn_amt`
  - 외화잔고 `GET /accounts/v1/me/overseas/currencies` → USD `frcr_amt`(예수금), `estm_frcr_amt`
  - 주문가능금액 `GET /trading/v1/overseas/orders/estimate` → USD 행 `oder_crnc_oder_able_camt`
  - 당일 주문체결 `GET /trading/v1/overseas/orders/today`, 기간 주문체결 `GET /trading/v1/overseas/orders/history`(`oder_sta_date`/`oder_fin_date` US, `cntg_cls_code`, 연속조회 30건) → `oder_no`, `oder_date`, `seln_byov_clcd`(1매도/2매수), `cntg_dqty`/`cntg_qty`, `cntg_crnc_prc`, `cntg_camt`, `oder_rctf_cncl_cls_code`(0원주문/1정정/2취소), `ovrs_stck_oder_stat_cls_code`(0정상/1거부/2자동거부/3자동취소/4미접수)
  - 매수/매도 `POST /trading/v1/overseas/orders/{buy|sell}` body `ovrs_stck_symb_iscd, ovrs_exch_vndr_idno, oder_cls_code, oder_cond_cls_code, odqt, ovrs_stck_oder_prc(소수 4자리 이하 문자열), warn_cnfr_yn?` → `rsp_cd 0001` + `data{oder_no, warn_cls_code, warn_msg}`
  - 취소 `POST /trading/v1/overseas/orders/cancel` body `oder_date(US), orgl_oder_no, oder_cls_code(원주문 그대로), odqt`
- 연속조회: 응답 본문 `tr_cont="1"` + `tr_cont_key`(오른쪽 공백 포함, 그대로 쿼리스트링으로 재전송). `rsp_cd 5762`일 때만 이어 조회.
- 주문체결내역은 **주문당 1행·누적 체결**(실측 733건: `oder_no` 중복 0, 정정/취소 행 0) — `cntg_dqty`=누적 체결수량, `cntg_crnc_prc`=평균 체결가, `cntg_camt`=체결금액. 부분 체결 후 장 마감 만료는 `ovrs_stck_oder_stat_cls_code="3"`(자동취소)·`ncnt_odqt=0`. KIS와 같은 형태라 `TradingReporter`의 주문번호별 합산 계약과 맞는다. 당일 목록 수량 필드는 `cntg_qty`(기간 목록은 `cntg_dqty`).
- 주문 목록에는 `oder_cls_code`가 없고 `aspr_type_code`만 있다(`1`시장가 `2`지정가 `4`LOO `5`LOC `6`MOO `7`MOC `8`LimitVWAP `9`LimitTWAP `A`VWAP `B`TWAP — 실측 `2`/cond`0`=지정가, `7`/cond`7`=MOC 확인). 취소 시 `oder_cls_code`는 이 표로 역매핑한다(`2→01, 5→32, 7→34, 4→31, 6→33, 1→05, 8→35, 9→36, A→37, B→38`).
- 당일 주문은 장 마감 시 거래소에서 만료된다(실측: 장중 접수 지정가가 `ovrs_stck_rfus_reas="Trading Sequence Completed"`, 미체결 0으로 종료) → KIS처럼 마감 후 명시 취소(`TradingReporter.cancelUnresolvedOrders`, Toss 전용) 불필요.

## 결정된 설계

### 주문 유형 매핑 (`oder_cls_code`/`oder_cond_cls_code`/가격)

| kista OrderType | BUY | SELL |
|---|---|---|
| LOC | `32`/`7`/단가 | `32`/`7`/단가 |
| MOC | `32`/`7`/캡 단가(KIS와 동일 — 매수 MOC 대신 LOC 캡) | `34`/`7`/`0` |
| LIMIT | `01`/`0`/단가 | `01`/`0`/단가 |
| MOO | `01`/`0`/캡 단가(`supportsOpenAuctionOrders=false` — 개장 후 지정가 근사) | `05`/`0`/`0` 시장가 — DCA SELL의 "즉시 매도" 의미(KIS `31`+0, Toss 0.01 지정가와 동일 의도). 내 결정: 0.01 지정가는 가격 괴리 경고(`warn_cls_code=1`)를 유발할 수 있어 시장가가 더 안전. 미실측 |
| MARKET | `IllegalArgumentException`(코인 전용, KIS와 동일) | 동일 |

가격은 `BigDecimal.setScale(2, HALF_UP).toPlainString()`(KIS와 동일 정밀도, 메리츠 한도 4자리 이내).

### 경고 재전송

주문/취소 응답 `warn_cls_code`: `0` 접수 → 성공. `1`(경고)·`3`(PTP 과세 확인) → 같은 body에 `warn_cnfr_yn="Y"`를 넣어 **1회 자동 재전송**(사용자 결정 — 최초 요청은 미접수가 확정이므로 이중 주문 아님). 재전송 응답도 `0`이 아니거나 `2`(보류)·그 외 → `MeritzApiException.rejected`(확정 거절). 자동 확인한 `warn_msg`는 WARN 로그에 남긴다.

### 오류 → 예외 매핑 (`BrokerApiException.isRejection` 계약)

- `MeritzApiException extends BrokerApiException`(vendor MERITZ, label "Meritz"), `broker.domain.model.meritz`.
- 확정 거절(`rejected=true`): 송신 전 실패(토큰 획득 실패, 호출 게이트 대기 초과/인터럽트), 본문 파싱 가능 + `rsp_cd`가 성공 코드 아님, `warn_cls_code` 미접수.
- 결과 불명(`rejected=false`): I/O·타임아웃(`RestClientException`), 본문 파싱 불가(빈 본문/비JSON — 게이트웨이 장애).
- `EGW00103`/`0760`/`EGW00202` → `BrokerCredentialException`/`BrokerPermissionDeniedException`(기존 공용 타입). `EGW00200` → GET만 백오프 재시도 후 `BrokerRateLimitException`. `EGW00121`/`EGW00123` → 거절 토큰 조건부 무효화 + 재발급 후 1회 재시도(POST 포함 — 토큰 거절은 게이트웨이 단계라 미접수가 확정).
- 주문·취소 POST는 그 외 어떤 경우에도 자동 재시도하지 않는다.
- `Conflict`(이미 체결/취소): 메리츠 취소 오류 코드가 실측되지 않았으므로 1차는 KIS처럼 `NONE`.

### 토큰

`broker_tokens`(account_id PK, 브로커 무관) + `BrokerTokenCachePort`를 재사용 — 신규 테이블·마이그레이션 없음, 계좌 삭제·키 교체 시 기존 `AccountTokenEvictionAdapter`가 DB 행을 지워 별도 작업 없음. `MeritzTokenCoordinator`는 `KisTokenCoordinator`와 같은 계약(계좌별 락·더블체크·만료 1분 전 갱신·`recover`)을 따른다. 빌더는 KIS 코디네이터를 공용화할지(발급 함수만 다름) 복제할지 결정 — 공용화가 작으면 공용화 우선(코드 철학: 재사용).

### 호출 간격

앱키별 최소 간격 게이트(KIS `KisHttpClient`의 per-appKey gate와 동일 기법, 간격 ≥ 110ms ≈ 9 TPS, 최대 대기 20s).

### Broker 상수·계좌·정책

- `Broker.MERITZ("메리츠증권", "메리츠", US_STOCK, USD)`.
- `TradingPolicySettings.defaults()`에서 MERITZ를 `BrokerSettings(false, null)`로 오버라이드(기본 비활성). 기존 운영 정책 JSON은 `backfillMissingEnumKeys`가 채운다.
- `AccountService.register`: MERITZ는 UPBIT처럼 `accountNo=null` 허용·appKey/secret 필수. `Account.dedupeKeyOf`: MERITZ는 appKey. 키 교체 시 appKey 중복 검사도 UPBIT와 동일하게 MERITZ 포함.
- `AccountRequest` `@Schema allowableValues`에 MERITZ 추가.
- 연결 테스트(`MeritzAuthApi implements BrokerConnectionTestPort`): 토큰 발급 + 외화잔고 조회 1회. `verifyAccount` → `(brokerAccountCode=null, apiKeyExpiresAt=null)`.

### capability 구현 (`MeritzBrokerAdapter implements BrokerCapabilitiesPort`)

- 가격: `getPrice(s)`=현재가 `prpr`, `getPrevClose(s)`=`prdy_clpr`, `getPriceSnapshot(s)`=현재가+전일종가, `getClosingPrice(s)(ticker, KST tradeDate)`=일봉에서 US 세션일(`UsTradeDates.toUsTradeDate`)의 `prpr` — 해당 일자 봉이 없으면 KIS `fetchConfirmedClose`와 같은 폴백 규칙을 따른다(빌더가 KIS 계약 확인). 다건은 종목별 순회.
- 잔고: `getLiveBalance`·`getUsdBuyableAmount`=주문가능금액 USD, `getPresentBalance`·`getMargin`=잔고+외화잔고를 기존 중립 타입으로 매핑(KIS 매핑 의미를 따름), `getSellableQuantity`=`seln_able_dqty`.
- 체결: `getExecutions(from,to,ticker)`=기간 주문체결내역(US 날짜 변환, 연속조회 — `rsp_cd 5762`일 때만, 최대 페이지 상한 둠), 원주문 행·`cntg_dqty>0`·`ovrs_stck_symb_iscd` 일치만, `Execution(UsTradeDates.toKstTradeDate(oder_date), ticker, seln_byov_clcd 1=SELL/2=BUY, cntg_dqty, cntg_crnc_prc, cntg_camt, oder_no)`. KIS `KisTradingApi.java:176-189`와 같은 규칙.
- 주문 `place` → `OrderResult(oder_no 문자열)`.
- `cancel`: `CancelInstruction`은 ticker·외부 주문번호뿐이다(`CancelInstruction.java:6`). 메리츠 취소는 `oder_date`·`oder_cls_code`·`odqt`가 필요하므로 어댑터가 **당일 주문체결내역에서 `oder_no`로 원주문을 조회**해 채운다(포트 시그니처 유지 — 내 결정). `odqt`=`oder_able_qty`(정정·취소 가능 수량 — 잔량 취소), `oder_cls_code`=`aspr_type_code` 역매핑, `oder_date`=행의 `oder_date`.
  - 조회 결과 `oder_able_qty=0`(이미 종결): 체결 수량이 주문 수량과 같으면 `Conflict.ALREADY_FILLED`, 아니면 `Conflict.ALREADY_CANCELED`로 던져 기존 Toss 충돌 처리 경로를 탄다(취소 API 호출 안 함).
  - 당일 목록에 없음: 최근 7일 기간 주문체결내역으로 한 번 더 찾고, 그래도 없으면 결과 불명 `MeritzApiException`(rejected=false) — 접수 직후 목록 지연 가능성이 있어 "이미 취소"로 단정하지 않는다.

### 설정

`meritz.base-url: https://openapi.imeritz.com:9443`(trading-core application.yml), `MeritzConfig`의 `meritzRestClient`(JDK 타임아웃 3s/10s). 서버 비밀값 없음(계좌별 키는 DB).

### kista-ui

계좌 등록 화면에 메리츠 선택지(앱키·시크릿 입력, 계좌번호 입력 없음 — UPBIT 폼과 같은 형태), 계좌 목록에서 계좌번호 대신 별칭 표시. 증권사 목록은 `/api/meta`에서 자동 반영. **UI 작업은 별도 kista-ui 세션에서** 수행 — api 커밋 후 사용자에게 UI 세션을 요청한다.

### 문서

`docs/agents/meritz-api.md`(위 실측 사실), README 증권사 목록, `modules/shared/kernel.md`·`tradingcore/broker.md`·`tradingcore/account.md`·`api/admin.md`의 브로커 목록, ArchUnit 벤더 패키지 allowlist(`HexagonalArchitectureTest`, `TradingCoreTestVendorModelTest`)에 `meritz` 추가. `flows.yml`은 흐름이 바뀌지 않으므로 증권사 나열 문구만 필요 시 갱신.

## 빌더 재량

- `KisTokenCoordinator` 공용화 vs 복제, HTTP 클라이언트 내부 구조(파일 분할: Http/Auth/Order/Price/Trading 등 KIS 대칭 권장).
- 응답 record 정의 방식, 숫자 문자열/숫자 혼재 파싱 헬퍼.
- MOO SELL 근사 매핑의 정확한 값(기존 호출부 계약 확인 후 결정, 근거를 커밋에 남김).

## 검증

- 단위: `MockRestServiceServer` 기반 Http 클라이언트(판정 순서·HTTP 500 업무 오류·토큰 재발급·GET 한도 재시도·POST 무재시도), 주문 매핑 `@ParameterizedTest`, 경고 재전송, 체결 매핑(US↔KST), 계좌 등록 MERITZ 분기, 정책 기본 비활성.
- 실 API: 조회 capability(가격·잔고·체결·연결 테스트)는 로컬 trading-core + 본인 키로 1회 확인. 실주문·취소는 **구현 완료 후 로컬에서 SOXL 1주를 체결 불가 가격(현재가의 약 50%) 지정가 매수 → 즉시 취소**로 확인한다(사용자 결정 — 실행 직전 사용자에게 한 번 더 알린다). 응답의 `warn_cls_code`·취소 결과·당일 목록 반영 시점을 `meritz-api.md`에 기록. LOC·MOC·시장가 매도는 미검증으로 남고 관리자 활성화 후 첫 배치에서 확인.
