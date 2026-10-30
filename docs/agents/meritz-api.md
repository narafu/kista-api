## 메리츠증권 API

2026-10-23 운영 서버 조회 전용 실측 기준. 구현은 `com.kista.tradingcore.broker.adapter.out.meritz`(벤더 모델 `domain.model.meritz`, internal). 메리츠 Open API는 2026-10-26 00시(KST) 정식 오픈(베타 종료)됐다. 모의 서버가 없어 `Broker.MERITZ`는 관리자 설정에서 **기본 비활성**이다.

### 기본 사실
- Base `https://openapi.imeritz.com:9443`(`meritz.base-url`, trading-core `application.yml`). 모든 요청 헤더 `authorization: Bearer <token>` + `mac_address`(고정 상수 `0242AC110002`, 값 검증 없음).
- 계좌번호가 없다: 앱키에 계좌 1개가 묶이며 요청에 `acno`·`asno`·`acnt_pwd`를 넣으면 실패한다. UPBIT처럼 `accountNo=null`, appKey가 식별자(`Account.dedupeKeyOf`)다.
- 토큰: `POST /oauth2/token`(form `grant_type=client_credentials&client_id&client_secret&scope=login`) → `access_token`, `expires_in`(43200), `expires_date`. 만료 전 재발급은 같은 토큰·같은 `expires_date`를 돌려준다(멱등). 만료는 **`expires_date`(KST) 기준**으로 잡는다 — `expires_in`은 상대값이 아니라 캐시가 최대 12시간 늦게 만료돼 `EGW00123`이 폭주한다. `oauth2/revoke`는 같은 앱키의 유일한 토큰을 끊으므로 호출 금지. 토큰 저장은 `broker_tokens` + `BrokerTokenCachePort`, 조정은 `DbTokenCoordinator`(KIS와 공용).
- 숫자 필드는 문자열/숫자 혼재(`"417.0000000000"`, `36`) — 파서는 둘 다 받는다(수량을 `Integer.parseInt`하면 잔고 조회 전체가 실패).
- 날짜(`date`, `oder_date`, `oder_sta_date`/`oder_fin_date`, `from`/`to`)는 **미국 거래일**. KST↔US 변환은 `UsTradeDates`만 쓰며 `MeritzPriceApi`·`MeritzTradingApi`가 allowlist에 있다 → constraints.md "시간 기준 정책".

### 응답 판정 (HTTP 상태코드 아님)
모든 업무 오류가 HTTP 500으로 오고(`EGW00103`만 403 가능), 성공도 500일 수 있다. `MeritzHttpClient`는 본문을 먼저 파싱한다.
1. 본문 JSON 파싱(HTTP 상태 무시). 파싱 불가(빈 본문/비JSON)는 게이트웨이 장애 → 결과 불명(`rejected=false`).
2. 주문/취소면 `data.warn_cls_code` 확인(아래 경고 규칙).
3. `rsp_cd` ∈ {`0000`,`0001`,`5620`,`5762`,`5766`,`5820`,`5822`}이면 성공, 그 외는 확정 거절(`rejected=true`).
4. 자료 유무는 `data`가 비었는지로 판단(`5820`이어도 data가 있을 수 있음).

오류 코드: `EGW00121`/`EGW00123` 토큰 무효·만료 → 거절 토큰 조건부 무효화 후 재발급, **1회** 재시도(POST 포함 — 게이트웨이 단계 거절이라 미접수 확정). `EGW00200` 초당 한도(API별 10 TPS) → GET만 백오프 재시도 후 `BrokerRateLimitException`. `EGW00103`(잘못된 client_id)·`0760`/`EGW00202`(권한 없음) → `BrokerCredentialException`/`BrokerPermissionDeniedException`(재시도 금지). `IGW50024` mac 헤더 누락.

호출 간격: 앱키별 최소 110ms(≈9 TPS), 최대 대기 20s(`CallIntervalGate`). 주문·취소 POST는 멱등키가 없어 위 토큰 재발급 1회와 경고 재전송 1회 외에는 자동 재시도하지 않는다. 연속조회는 본문 `tr_cont="1"` + `tr_cont_key`를 **공백 포함 그대로** 쿼리로 재전송하고 `rsp_cd 5762`일 때만 이어 조회, 페이지 상한 50.

### 엔드포인트
| 용도 | 호출 | 주요 필드 |
|---|---|---|
| 현재가 | `GET /market/v1/overseas/prices`(`iscd`, `mrkt_div_code=OV`, `mrkt_cls_code`, `dely_rltm_cls_code=0`) | `prpr`, `prdy_clpr` |
| 일봉(확정 종가) | `GET /market/v1/overseas/candles/days`(`mod_stpr_cls_code` 필수, `from`/`to` US) | `date`, `prpr`(종가) |
| 잔고 | `GET /accounts/v1/me/overseas/holdings`(연속조회) | `ovrs_stck_symb_iscd`, `rmnd_dqty`(판매가능수량 포트도 이 값 — `seln_able_dqty`는 미체결 매도 차감 순수량이라 쓰지 않음, `SellableQuantityPort` 계약. 2026-10-28 실측: SOXL 미체결 매도 1주 중 `rmnd_dqty=345`·`seln_able_dqty=344`), `avrg_unpr`, `psnt_prc`, `frcr_vltn_amt` |
| 외화잔고 | `GET /accounts/v1/me/overseas/currencies` | USD `frcr_amt`(예수금), `estm_frcr_amt` |
| 주문가능금액 | `GET /trading/v1/overseas/orders/estimate` | USD 행 `oder_crnc_oder_able_camt` |
| 당일 주문체결 | `GET /trading/v1/overseas/orders/today` | 수량 `cntg_qty` |
| 기간 주문체결 | `GET /trading/v1/overseas/orders/history`(`oder_sta_date`/`oder_fin_date`, `cntg_cls_code`, 30건 연속조회) | 수량 `cntg_dqty` |
| 매수/매도 | `POST /trading/v1/overseas/orders/{buy\|sell}` | 응답 `rsp_cd 0001` + `data{oder_no, warn_cls_code, warn_msg}` |
| 취소 | `POST /trading/v1/overseas/orders/cancel`(`oder_date`(US), `orgl_oder_no`, `oder_cls_code`(원주문 그대로), `odqt`) | |

거래소 코드: 주문·잔고는 벤더코드 `ovrs_exch_vndr_idno`(NASDAQ `0537`, AMEX `0066`, NYSE `0321`), 시세는 `mrkt_cls_code`(`OQ`/`AX`/`NY`). 실측 TQQQ=OQ/0537, SOXL·USD·MAGX·UPRO·QLD=AX/0066. 매핑 SSOT는 `MeritzExchangeRegistry`.

주문체결내역은 **주문당 1행·누적 체결**(실측 733건, `oder_no` 중복 0): `cntg_dqty`=누적 체결수량, `cntg_crnc_prc`=평균 체결가, `cntg_camt`=체결금액. `seln_byov_clcd` 1매도/2매수, `oder_rctf_cncl_cls_code` 0원주문/1정정/2취소, `ovrs_stck_oder_stat_cls_code` 0정상/1거부/2자동거부/3자동취소/4미접수. 부분 체결 후 장 마감 만료는 `3`·`ncnt_odqt=0`. `getExecutions`는 원주문 행·체결수량>0·종목 일치만 `Execution`으로 만든다(KIS와 같은 규칙).

### 주문 유형 매핑 (`oder_cls_code`/`oder_cond_cls_code`/가격)
| kista OrderType | BUY | SELL |
|---|---|---|
| LOC | `32`/`7`/단가 | `32`/`7`/단가 |
| MOC | `32`/`7`/캡 단가(KIS와 동일 — 매수 MOC 대신 LOC 캡) | `34`/`7`/`0` |
| LIMIT | `01`/`0`/단가 | `01`/`0`/단가 |
| MOO | `01`/`0`/캡 단가(`supportsOpenAuctionOrders=false` — 개장 후 지정가 근사) | `05`/`0`/`0` 시장가(DCA "즉시 매도" 의도) |
| MARKET | `IllegalArgumentException`(코인 전용) | 동일 |

가격은 `setScale(2, HALF_UP).toPlainString()`(한도 4자리 이내). 단가·수량 누락은 송신 전에 차단한다.

### 경고 재전송
주문/취소 응답 `warn_cls_code`: `0` 접수 → 성공. `1`(경고)·`3`(PTP 과세 확인) → 최초 요청은 미접수가 확정이므로 같은 body에 `warn_cnfr_yn="Y"`를 넣어 **1회** 재전송하고 `warn_msg`는 WARN 로그. 재전송 응답도 `0`이 아니거나 `2`(보류)·그 외는 확정 거절.

### 취소
`CancelInstruction`에는 ticker·외부 주문번호뿐이라 어댑터가 **당일 주문체결내역에서 `oder_no`로 원주문을 조회**해 `oder_date`·`oder_cls_code`·`odqt`를 채운다. `odqt`=`oder_able_qty`(잔량), `oder_cls_code`는 목록의 `aspr_type_code` 역매핑(`1시장가→05, 2지정가→01, 4LOO→31, 5LOC→32, 6MOO→33, 7MOC→34, 8→35, 9→36, A→37, B→38`).
- `oder_able_qty=0`(이미 종결): 체결수량이 주문수량과 같으면 `CancelOutcome.ALREADY_FILLED`, 아니면 `ALREADY_CANCELED`를 반환하고 취소 API는 부르지 않는다.
- 당일 목록에 없음: 최근 7일 기간 목록으로 한 번 더 찾고, 그래도 없으면 결과 불명(`rejected=false`) — 접수 직후 목록 지연 가능성이 있어 "이미 취소"로 단정하지 않는다.
- LOC 등 조건부 주문은 장 마감 시 거래소가 만료시킨다(실측 `ovrs_stck_rfus_reas="Trading Sequence Completed"`). 그러나 **지정가는 애프터장까지 살아 있다** — 실측 2026-10-29 거래일 SOXL 매수 지정가 43주 @100.62(주문번호 1878526)가 04:30:02 KST 접수 → 06:10:21 KST 애프터장 체결. 그래서 마감 리포트(`TradingReporter.cancelUnresolvedOrders`, 전 증권사 공통)가 체결 조회 직전에 잔여 PLACED를 명시 취소한다. 이미 종결된 주문은 위 `oder_able_qty=0` 판정으로 `CancelOutcome`이 되어 리포트가 조용히 넘긴다. (취소 없이 05:10 리포트가 CANCELLED로 기록하면 애프터장 체결이 원장에서 누락된다. 원장 대사 `LedgerReconciliation`은 실보유<원장만 경보해 이 매수 누락은 감지하지 못하므로 취소가 방어선이다.)

### 접수 시각
kista 접수는 전부 정규장 안이다(마감 배치 AT_CLOSE, 개장 배치는 개장 후). 예약주문 API 불필요. 최악 경로(게이트 20s + 읽기 10s + 토큰 재시도 10s) ≈ 40s로 `SubmittingOrderAwaiter` 60s 예산 안쪽.

### 실주문 실측 (2026-10-23 23:04 KST, 정규장, `MeritzLiveIT`)
SOXL 1주 매수 @현재가×0.5 → 즉시 취소를 LOC·지정가로 각 1회.
- **LOC(`32`/`7`)·지정가(`01`/`0`) 모두 정상 접수** — `warn_cls_code="0"`(경고 없음), 접수 응답 약 140ms.
- **당일 목록 반영**: 접수 후 첫 조회(약 90ms 뒤)에 이미 보인다. 행의 `aspr_type_code`는 LOC=`5`, 지정가=`2`(역매핑 표와 일치), `oder_able_qty`=1.
- **취소**: 정상 처리, 약 650ms 뒤 원주문 행 `oder_able_qty=0`·`cntg_qty=0`. 취소는 당일 목록에 **별도 행**으로 생긴다 — 취소 행은 새 `oder_no`, `oder_rctf_cncl_cls_code="2"`, `orgl_oder_no`=원주문 번호, `cncl_odqt`=취소 수량. 원주문 행은 `oder_rctf_cncl_cls_code="0"` 그대로이고 `cncl_odqt=0`이다. 어댑터의 원주문 조회(`oder_no` 일치)와 체결 필터(`oder_rctf_cncl_cls_code=="0"`)는 이 형태와 맞는다.

### 첫 운영 배치 확인 (2026-10-27)
정식 오픈(2026-10-26) 후 기존 앱키로 토큰 발급·접수가 그대로 동작했다(`EGW00103`/`EGW00121` 없음). PRIVACY SOXL 지정가 4건(매수 1·매도 3) 전부 접수 성공, 마감 후 체결 조회로 매도 1건 FILLED(지정가 84.58 → 체결가 92.28, 시장가 위 지정가 매도라 즉시 체결)·나머지 3건 미체결 CANCELLED 반영.

### 미검증
실주문 실측 이후에도 남은 항목 — 관리자 활성화 후 첫 배치에서 확인한다.
- MOC(매도 `34`)·시장가 매도(`05`, MOO SELL 근사) 실접수 여부(LOC·지정가 매수는 실측 완료).
- `warn_cls_code`가 `0`이 아닌 경우의 실제 형태 — 실측·첫 운영 배치(2026-10-27)에서 경고가 한 번도 발생하지 않았다. 경고가 `data.warn_cls_code`가 아니라 성공이 아닌 `rsp_cd`로 올 가능성이 남아 있다 — 이 경우 `MeritzHttpClient`가 먼저 예외를 던져 재전송이 동작하지 않는다(미접수 방향이라 안전).
- 취소 오류 코드(이미 체결·이미 취소된 주문에 대한 취소 응답) — `CancelOutcome`은 목록 기반 판정만 하고 응답 코드로는 판정하지 않는다.

### 잔고 부족 거절 분류 갭
- 메리츠는 잔고 부족을 나타내는 `rsp_cd`가 확인되지 않아 `isInsufficientFunds()`로 분류하지 않는다. 잔고 부족 거절도 일반 확정 거절과 같이 관리자 알림으로 남는다 — 실측으로 코드를 확인하면 `MeritzHttpClient`에서 분류를 추가한다. 분류 전까지 메리츠 사용자에게는 미리보기 `accountCashCheck`가 사전 신호다.
