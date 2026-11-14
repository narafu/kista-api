# 26. SUBMITTING 잔류 복구 — 메리츠 조회 API로 접수 여부를 확정할 수 있는가 (스펙 기준 조사)

조사일 2026-11-15. 스펙: `/Users/phs/Downloads/meritz_openapi_spec_20261112.json`(generatedAt 2026-11-12 06:06:18). 코드·실계좌 변경/호출 없음. 사실만 적고 판정·권고는 쓰지 않는다.
스펙 경로 표기: `groups[3]`(해외주식).`apis[n]`.`trs[0]`.`request|response`. n: 0 매수, 1 매도, 5 당일 주문체결(`/orders/today`, code `obtro_oder_103r`), 14 기간 주문체결(`/orders/history`, `obtro_oder_201r`), 15 주문체결 상세(`/orders/detail`, `obtro_oder_271r`).

## 1. 요약

- 메리츠 매수/매도 접수 요청(`apis[0]`,`apis[1]`, 바디 필드 동일)에는 클라이언트가 지정하는 주문 식별값·멱등 키 필드가 **없다**. 요청 바디는 종목·거래소·주문구분·조건·수량·가격·경고확인 7개뿐이다.
- 응답을 받으면 `oder_no`(11자리 숫자)가 식별값이다. 응답을 못 받으면 우리 쪽에는 `oder_no`가 없다(`Order.externalOrderId`는 PLACED 이후에만 설정). 우리가 가진 것은 주문 UUID(전송은 안 됨)·종목·방향·유형·수량·가격·선점 시각(`SubmittingOrder.submittedAt`)이다.
- 조회 API 3종 중 `oder_no` 없이 쓸 수 있는 것은 당일(`/orders/today`)·기간(`/orders/history`) 목록이다. 둘 다 종목·주문번호 필터가 없고 매매구분·체결구분만 거를 수 있다. 상세(`/orders/detail`)는 `oder_no` 필수라 응답을 못 받은 주문에는 쓸 수 없다.
- 행의 접수 시각 `oder_exec_ymdh`는 "YYYY-MM-DD HH:MM:SS"(초 단위)다. 타임존 표기는 스펙에 없다. 거부·자동거부·미접수 상태 코드와 `ovrs_stck_rfus_reas` 필드는 있으나, **동기 거절(HTTP 500 응답)된 주문이 목록에 행으로 남는지는 스펙에 적혀 있지 않다**.
- 한 계좌·한 종목에 활성 전략은 하나(DB 유니크)지만, 한 전략이 같은 (방향·유형·수량·가격) 주문을 서로 다른 leg로 둘 이상 낼 수 있는 경로가 코드상 존재한다(DCA 다중 규칙, 프라이버시 기준표 행). 따라서 (계좌, 거래일, 종목, 방향, 유형, 수량, 가격)만으로는 유일성이 보장되지 않는다(D절).
- 토스 복구는 조회가 아니라 **같은 멱등키(`clientOrderId`=주문 UUID)로 재요청**해 원 주문번호를 돌려받는 방식이다. 메리츠는 `OrderInstruction.clientOrderId`를 보내지 않는다.

## 2. 절별 사실

### A. 접수 요청 필드 (`apis[0]`·`apis[1]`, POST `/trading/v1/overseas/orders/buy|sell`)

헤더: `content-type`(Y, "application/json; charset=utf-8"), `authorization`(Y, 토큰), `mac_address`(Y, 12자리, "호출 단말의 MAC 주소… 넣지 않으시면 요청이 거부됩니다").

| 바디 필드 | 필수 | 길이 | 스펙 설명 요지 |
|---|---|---|---|
| `ovrs_stck_symb_iscd` | Y | 12 | 해외주식 심볼 |
| `ovrs_exch_vndr_idno` | Y | 6 | 거래소 코드(0321 NYSE, 0537 NASDAQ, 0066 AMEX …) |
| `oder_cls_code` | Y | 2 | 01 지정가, 05 시장가, 31 LOO, 32 LOC, 33 MOO, 34 MOC, 35~38 VWAP/TWAP 계열 |
| `oder_cond_cls_code` | N | 1 | 0 조건없음, 2 개장전, 7 종가 |
| `odqt` | Y | 19 | 주문 수량 |
| `ovrs_stck_oder_prc` | Y | 20 | 주문 단가(소수 4자리까지, 초과 시 `IGW50002`) |
| `warn_cnfr_yn` | N | 1 | 경고 확인 여부 N/Y |

- client order id·멱등 키·사용자 정의 필드·메모 필드는 요청 스키마 어디에도 없다. 요청 예시도 6개 필드뿐(`apis[0].trs[0].requestExample`).
- 계좌 식별 필드도 없다(앱키가 계좌를 식별 — `docs/agents/vendors/meritz.md` "기본 사실").
- 코드 쪽 송신 바디: `MeritzOrderApi.java:85-91`이 위 필드만 채운다. `OrderInstruction.clientOrderId`(`OrderInstruction.java:16` "Toss만 사용, KIS·Mock 무시")는 메리츠에서 쓰지 않는다. `OrderSubmitter.instructionOf`(`OrderSubmitter.java:93-96`)가 모든 브로커에 `order.id().toString()`을 실어 보낸다.

### B. 접수 응답 식별값 / 응답 불명 시 보유 정보

- 응답 `data.oder_no`(Number, 11): "이번 요청으로 채번된 주문 번호입니다. … orgl_oder_no 에 넣는 값입니다." 그 외 `warn_cls_code`(0 정상 접수, 1·3 경고→`warn_cnfr_yn=Y` 재전송, 2 보류), `warn_msg`, `rsp_cd`("0001": 주문 접수 성공. "오류는 종류와 무관하게 HTTP 500 으로 반환"), `rsp_msg`, `tr_cont`("0"), `rsp_sub_msg`(오류 시). 응답 예시 `oder_no: 284260`.
- 코드: `MeritzOrderApi.java:92-97` — `oder_no<=0`/해석 불가는 `MeritzApiException(... "접수 여부 불명")`. `Order.externalOrderId`(`Order.java:28`, `OrderEntity.java:72`)는 `markPlaced`(`OrderPersistenceAdapter.java:92-99`)가 PLACED 전이 때 채운다 → SUBMITTING 행에는 증권사 식별값이 없다.
- 응답을 못 받았을 때 행에 있는 것: `id`(UUID), `accountId`, `strategyCycleId`, `tradeDate`(KST), `ticker`, `orderType`, `timing`, `direction`, `orderLeg`, `quantity`, `price`(`Order.java:19-33`) + 선점 시각 `SubmittingOrder.submittedAt()`(`OrderSubmissionReconciler.java:53`). 가격 컬럼은 `precision 12, scale 2`(`OrderEntity.java` price).

### C. 조회 API 조건·응답

**C-1 당일 `/orders/today`(`apis[5]`)** 설명: "당일 건만 내려오므로 정정·취소 대상을 찾거나 방금 낸 주문의 체결 여부를 확인할 때 사용하십시오."
- 요청(쿼리): `cntg_cls_code`(N: 0 전체/1 체결/2 미체결), `seln_byov_clcd`(N: 0 전체/1 매도/2 매수). **날짜·종목·주문번호 조건 없음. 요청 필드에 `tr_cont`/`tr_cont_key`도 없다**(응답에는 `tr_cont` 항목이 있으나 키는 없음). 코드는 `getAllPages`로 호출(`MeritzOrderApi.java:153`).
- 응답 `data[]` 주요 필드: `oder_date`(YYYYMMDD), `oder_no`, `orgl_oder_no`(신규 0), `iscd`("PLTR.OQ"), `iscd_name`, `ovrs_stck_symb_iscd`(접미어 없는 심볼), `ovrs_exch_vndr_idno`, `seln_byov_clcd`(1 매도/2 매수), `aspr_type_code`(1 시장가/2 지정가/4 LOO/5 LOC/6 MOO/7 MOC/8~B), `oder_cond_cls_code`(응답 체계, 예시 "8"), `odqt`, `rctf_odqt`, `cncl_odqt`, `rctf_cnfr_qty`, `cncl_cnfr_qty`, `ovrs_stck_oder_prc`, `cntg_qty`, `cntg_crnc_prc`, `ncnt_qty`, `oder_able_qty`, `oder_rctf_cncl_cls_code`(0 원주문/1 정정/2 취소), `oder_exec_ymdh`("YYYY-MM-DD HH:MM:SS", "주문이 접수된 일시"), `ovrs_stck_oder_stat_cls_code`(0 정상/1 거부/2 자동거부/3 자동취소(체결후)/4 미접수), `onln_yn`, `ovrs_stck_rfus_idnt_code`, `ovrs_stck_rfus_reas`("정상 접수 건은 빈 값"), `deal_cls_code`, `oder_pros_time`(길이 9, 예시 "124823"), `rctf_cncl_cls_code`(코드 체계가 `oder_rctf_cncl_cls_code`와 다름), `oder_cfee`, `cntg_camt`, 대출 3필드, `dcpn_loeq_ciph`, `byov_unit_qty`, `seln_unit_qty`, `rsp_cd`("5766 조회 완료/5820 자료 없음"), `rsp_msg`, `tr_cont`, `rsp_sub_msg`.

**C-2 기간 `/orders/history`(`apis[14]`)** 설명: "정정·취소 건은 원주문과 별개의 행으로 내려오며, orgl_oder_no 는 최초 주문이 아니라 바로 직전 건을 가리킵니다."
- 요청(쿼리): `oder_sta_date`(Y, YYYYMMDD; "00000000"이면 전체 이력), `oder_fin_date`(Y; "00000000" 금지, 시작>종료는 `3174`), `seln_byov_clcd`(N; 목록 밖 한 자리 값은 0건 `5820`), `cntg_cls_code`(Y: 0/1/2), `tr_cont`(N), `tr_cont_key`(N, 직전 응답 값을 그대로). **종목·주문번호 조건 없음.** 한 페이지 30건, `rsp_cd 5762`/`tr_cont=1`이면 연속.
- 응답 `data[]`: `oder_date`, `oder_no`, `orgl_oder_no`("대상으로 삼은 주문의 번호"), `iscd`, `kor_isnm`, `seln_byov_clcd`, `oder_dqty`(String, 소수 허용), `ovrs_stck_oder_prc`, `cntg_dqty`(누적 체결수량으로 코드 문서화 — meritz.md), `cntg_crnc_prc`, `ncnt_odqt`("실제 미체결 수량과 다를 수 있으므로 … oder_dqty에서 cntg_dqty를 빼서"), `cntg_camt`, `ovrs_stck_oder_stat_cls_code`(0/1/2/3/4), `oder_rctf_cncl_cls_code`, `rctf_cncl_cls_name`, `oder_exec_ymdh`("주문이 접수된 시각… YYYY-MM-DD HH:MM:SS"), `cntg_ymdh`(미체결은 빈 값), `aspr_type_code`, `oder_cond_cls_code`, `ovrs_stck_mrkt_tr_cls_code`, `crcd`, `onln_yn`, `oder_cnfr_yn`, `cntg_no`("체결된 건도 0으로 내려옵니다"), `ovrs_stck_rfus_idnt_code`, `ovrs_stck_rfus_reas`, 대출 2필드, `entm_onsl_intg_clcd`(10 일반/30 소수점), `oder_amt`·`oder_unpr`(항상 0), `rsp_cd`(5762/5766/5820), `rsp_msg`, `tr_cont`, `tr_cont_key`, `rsp_sub_msg`.
- 당일 목록과 기간 목록은 수량 필드명이 다르다(`odqt`↔`oder_dqty`, `cntg_qty`↔`cntg_dqty`). 기간 목록에는 `oder_able_qty`가 없다.

**C-3 상세 `/orders/detail`(`apis[15]`)**: 요청 `oder_sta_date`(Y, 주문일자), `oder_no`(Y, "주문 접수 응답의 oder_no 또는 주문체결내역 조회 응답의 oder_no"). 응답은 기간 목록과 같은 계열 필드 + 체결 건별 행(`cntg_dqty`는 행 단위, 같은 주문 행을 모두 합산). 설명에 "거부된 주문의 사유(ovrs_stck_rfus_reas)"가 적혀 있다. `oder_no`를 모르는 주문에는 호출할 수 없다.

**주문 시각 정밀도**: 당일·기간·상세 모두 `oder_exec_ymdh`가 "YYYY-MM-DD HH:MM:SS" 19자 = **초 단위**. 밀리초 필드 없음. 타임존(KST/ET)·서버 시계 기준에 대한 기술은 스펙에 없다. (참고: meritz.md 실측 "04:30:02 KST 접수" 기록이 있어 KST로 쓰였다고 읽히나 스펙 원문은 아님.) `oder_date`는 meritz.md가 US 거래일로 기록.

**거부·취소 주문이 조회에 나오는지**
- 취소: 별개 행으로 내려온다고 스펙이 명시(`apis[14].description`, 필드 `oder_rctf_cncl_cls_code=2`). 실측은 meritz.md "실주문 실측 2026-10-23".
- 거부: 상태 코드 `1 거부/2 자동거부/4 미접수`와 `ovrs_stck_rfus_reas` 필드는 목록 스키마에 존재한다("주문이 거부된 경우의 사유"). 그러나 접수 API 응답에서 HTTP 500으로 거절된 주문(예 `5988`, `1543`, `IGW50002`)이 행으로 남는지, 어떤 경우에 상태 1/2/4가 생기는지는 스펙에 서술이 없다.

### D. 클라이언트 키 없이 (계좌, 거래일, 종목, 방향, 유형, 수량, 가격)+시각 창으로 유일 식별 가능한가

조회 쪽 조합 키로 쓸 수 있는 필드: `oder_date`, `ovrs_stck_symb_iscd`(또는 `iscd`), `seln_byov_clcd`, `aspr_type_code`(LOC=5, MOC=7, 지정가=2, 시장가=1 — 어댑터가 MOC 매수를 LOC로, MOO 매수를 지정가로, MOO 매도를 시장가로 보내므로 kista `OrderType`과 1:1 아님: `MeritzOrderApi.java:75-81`), `odqt`/`oder_dqty`, `ovrs_stck_oder_prc`(String, 예 "350.58000000"), `oder_exec_ymdh`(초). 계좌는 앱키 단위라 조회 자체가 한 계좌로 한정된다.

kista 쪽에서 같은 (계좌·거래일·종목·방향·가격·수량) 주문이 둘 이상 나올 수 있는지:
1. 계좌·종목당 활성 전략 1개: `V16__strategy_account_ticker_unique.sql:2-3`(`uq_strategy_account_ticker_active`, `WHERE deleted_at IS NULL AND status <> 'ARCHIVED'`), `docs/agents/modules/tradingcore/trading.md:27`. 한 전략당 열린 사이클 1개: `V21__ledger_unique_constraints.sql:12-14`. → "한 계좌에 같은 종목 전략이 둘"은 DB가 막는다.
2. 슬롯 유니크는 `(strategy_cycle_id, trade_date, timing, direction, order_leg)`이며 살아 있는 주문(PLANNED/SUBMITTING/PLACED/PARTIALLY_FILLED/FILLED)만 대상(`V21:6-9`), `TradingOrderSlots.java:19-25`도 leg까지 같아야 동일 슬롯. **가격·수량은 슬롯 키가 아니므로 leg가 다르면 같은 가격·수량 주문이 공존할 수 있다**(DB·코드 모두 막지 않음).
3. 같은 가격·수량이 실제로 나올 수 있는 생성 경로:
   - DCA 다중 규칙: 규칙마다 leg `"DCA_" + ruleKey`(`DcaRule.java:88-92`), `DcaCycleOrderStrategy.java:62-73`이 규칙 수만큼 `PlannedOrder`를 만들고 가격·수량은 규칙별 계산(`decideRule`). 서로 다른 규칙이 같은 방향·유형·수량·가격을 내는 것을 막는 코드는 찾지 못했다.
   - 프라이버시: BUY/SELL 각각 기준표 행마다 leg `PRIVACY_BUY_nn`/`PRIVACY_SELL_nn`을 순번 부여(`PrivacyStrategy.java:109-116,119-137`). 정렬 키가 방향·가격·유형·수량이라 정렬 코드는 동일 값 행의 공존을 전제로 한다(동일 값 행 병합 없음). 기준표에 같은 가격·수량 행이 있는지는 데이터 문제로 코드만으론 알 수 없다.
   - 인피니트 보정 매수: `INFINITE_CORRECTION_nn`은 수량 1주 LOC, 가격 `unitAmount/(totalQuantity+1)` 내림 2자리(`InfiniteStrategy.java:143-156`) — 가격이 순번마다 낮아지나 2자리 내림으로 동일해지는지는 코드로 단정 불가. 전반 매수①②는 같은 가격이면 `EARLY_MERGED_LEG`로 병합(`InfiniteStrategy.java:121-125`)해 공존하지 않는다.
   - VR: 매수는 같은 가격 그룹을 병합(`VrStrategy.java:~165-176`), 매도는 단별 가격 `position.sellPrice(s)`·1주(`VrStrategy.java:187-197`). 단별 가격이 같아지는지는 `VrPosition`을 보지 않아 확인 못 함.
4. 같은 주문이 FAILED 후 재생성되거나 취소 후 같은 슬롯으로 재접수되는 경우(제약 밖 행)에는 동일 값 주문이 시간차로 존재한다(`V21:7-8` "취소·실패 행은 제약 밖"). 당일 목록에는 과거 시도(취소 행 포함)도 남는다.
5. 같은 계좌(앱키)를 다른 kista 계좌 행이 공유하는지: 등록 시 `Account.dedupeKeyOf(broker, accountNo, appKey)`로 중복 검사(`AccountService.java:82`)하는 것까지 확인했다. 앱키를 사용자가 kista 밖에서(증권사 앱·HTS) 낸 주문도 같은 목록에 나온다(`onln_yn` "Y 온라인/N 영업점" 필드는 있으나 채널 구분 용도 서술은 없음).

### E. 토스 복구 방식 (file:line)

- 식별값: 멱등키 `clientOrderId` = kista 주문 UUID 문자열. `OrderSubmitter.java:93-96`(instructionOf)가 전달, `TossOrderApi.java:51-53`가 바디에 넣고 "10분 내 같은 키 재요청은 원 주문 결과 재반환"으로 주석. 조회 API는 쓰지 않는다 — `docs/agents/vendors/toss.md:53`: 주문 목록·상세 응답(`Order` 스키마)에 `clientOrderId`가 없어 사후 조회가 안 된다.
- 시점: 접수 경로 진입 시 `OrderSubmissionReconciler.reconcile`(`OrderSubmissionReconciler.java:46-67`, 호출처 `TradingOrderExecutor`의 `placeAtOpenOrders` 등). 선점 후 2분 미만은 다른 스레드 접수 중일 수 있어 건너뜀(`:36,54`), Toss이고 9분 미만(`:37`)일 때만 재요청(`:55`).
- 재요청: `brokerOrderCorrectionPort.place(instructionOf(latest), …)`(`:78`). 성공이면 반환된 `externalOrderId`로 `markPlaced`(`:87-89`). 확정 거절(`BrokerApiException.isRejection`)이면 `markFailed`(`:80-85`). 그 외 예외·409 `request-in-progress`(`TossHttpClient.java:160-166`, 결과 불명으로 분류)면 불명 유지(`:81-82`).
- 확정 못 하는 경우: 메리츠·KIS·9분 초과·여전히 불명이면 `unresolved`에 모아 `[접수 결과 불명] … 자동 재접수되지 않음, 증권사 확인 후 런북 정정` 관리자 알림(`:60-66`), 알림 중복 제거는 프로세스 메모리 Set(`:44,60`). 접수 직후 경로의 즉시 알림은 `OrderSubmitter.unknown`(`OrderSubmitter.java:119-126`). 재개·재캡·삭제 대상에서 SUBMITTING은 제외(`workflow-orders.md:8`).
- 메리츠에는 이 경로가 없고 `account.broker() == Broker.TOSS` 분기(`:55`)가 막는다. 메리츠 접수 POST는 토큰 재발급 1회·경고 재전송 1회 외 자동 재시도가 없다(`vendors/meritz.md` "응답 판정").

### F. 호출 한도·시간대 제약 (스펙 기준)

- 조회 3종 응답 설명에 공통: 오류는 종류와 무관하게 HTTP 500, 성공/실패는 `rsp_cd`로 판정, "초당 호출 한도를 넘기면 `EGW00200`이 내려오니 잠시 뒤 다시 보내십시오". 수치(TPS)는 스펙 JSON에 없다. 코드 문서의 "API별 10 TPS"·앱키별 최소 110ms 간격은 `vendors/meritz.md`(2026-10 실측 기록)에만 있다.
- 조회 가능 시간대·점검 시간·일일 호출 상한: 스펙 JSON에서 해당 문구를 찾지 못했다(키워드 TPS/초당/운영시간/조회 가능 시간/점검 검색, 위 `EGW00200` 문구 외 없음).
- 페이지 크기: 기간 목록 30건/페이지(`apis[14]` data 설명). 당일 목록의 페이지 크기·연속조회 키는 스펙에 없음. 상세는 항상 1건 배열(체결 건별 행 가능).
- 당일 목록은 "당일 건만"(스펙 문구). "당일"의 기준 시각(KST 자정/US 거래일)은 스펙에 없다. 기간 목록의 날짜는 meritz.md가 US 거래일로 기록.

### G. 스펙에 없어 실측이 필요한 항목

1. `oder_exec_ymdh`의 타임존·시계 기준(KST인지 ET인지), 서버 시각과 kista `submittedAt`의 오차 범위.
2. 동기 거절(HTTP 500, 예 `5988`/`1543`/`IGW50002`/`EGW00200`)된 주문이 목록에 행으로 남는지, 상태 `1/2/4` 행이 생기는 조건.
3. 응답 유실(타임아웃) 후 실제로 접수된 주문이 목록에 나타나는 지연(meritz.md 실측은 정상 응답 후 약 90ms에 보임 — 유실 케이스 실측 없음).
4. 같은 요청이 중복 전송됐을 때 증권사가 거절하는지(중복 주문 방지 로직 유무). 스펙에 멱등·중복 방지 서술 없음.
5. 당일 목록(`/orders/today`)의 정렬 순서, 페이지 크기, 연속조회 지원 여부, "당일"의 경계.
6. `oder_exec_ymdh` 동초(同秒) 다건 시 `oder_no` 단조 증가 여부(주문번호가 시간순인지 스펙에 없음).
7. 시장가 매도(`05`)·MOC 매도(`34`)의 `aspr_type_code` 실제 값(meritz.md "미검증").
8. 가격 필드 동치 비교: 요청 가격 `"100.62"`와 응답 `"100.62000000"`(8자리)의 일치, kista는 2자리 HALF_UP 전송(`vendors/meritz.md` 주문 유형 매핑).
9. HTS·앱(영업점 포함)에서 낸 주문이 같은 목록에 섞이는 것의 실제 빈도(`onln_yn` 의미 외 서술 없음).
10. 호출 한도 수치·조회 가능 시간대·점검 시간 공식 값(위 F).

## 3. 근거 원문 위치

- 매수 요청 필드표·응답 필드표·예시: `groups[3].apis[0].trs[0].request.body[]`, `.response.body[]`, `.requestExample`, `.responseExample`. 매도(`apis[1]`)는 요청 바디 필드 목록이 매수와 동일(스크립트 비교 확인).
- 당일 조회 필드: `groups[3].apis[5].trs[0]` (`request.body`: `cntg_cls_code`·`seln_byov_clcd`; `response.body[]` `oder_exec_ymdh`, `ovrs_stck_oder_stat_cls_code`, `ovrs_stck_rfus_reas`, `oder_rctf_cncl_cls_code`, `oder_pros_time` …).
- 기간 조회: `groups[3].apis[14]` (description "정정·취소 건은 원주문과 별개의 행으로 내려오며…", `request.body` 6개, `response.body[]`의 `data` 설명 "한 페이지에 30건까지").
- 상세 조회: `groups[3].apis[15]` (description "…거부된 주문의 사유(ovrs_stck_rfus_reas)까지…", `oder_no` "주문 접수 응답의 oder_no 또는 주문체결내역 조회 응답의 oder_no").
- 한도: 조회 3종 `response.body[].rsp_cd` 설명의 `EGW00200` 문구.
- 코드: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/OrderSubmissionReconciler.java`, `.../trading/application/service/OrderSubmitter.java`, `.../broker/adapter/out/meritz/MeritzOrderApi.java`, `.../broker/adapter/out/toss/TossOrderApi.java`, `.../broker/adapter/out/toss/TossHttpClient.java`, `.../trading/domain/model/Order.java`, `.../trading/adapter/out/persistence/OrderEntity.java`, `.../trading/application/service/TradingOrderSlots.java`, `.../matching/domain/strategy/{DcaCycleOrderStrategy,PrivacyStrategy,InfiniteStrategy,VrStrategy}.java`, `trading-core/src/main/resources/db/migration-trading/{V16,V21}*.sql`.
- 문서: `docs/agents/vendors/meritz.md`, `docs/agents/vendors/toss.md:53`, `docs/agents/workflow-orders.md:8`, `docs/agents/modules/tradingcore/trading.md:27`, `docs/superpowers/fable-review/guides/12-features.md:152`(B6), `findings/24-guide-premises-08-15.md:277`(F4).

## 4. 판정 (S14 실행 세션, 2026-11-15)

질문: 메리츠 주문 조회로 접수 불명(SUBMITTING 잔류) 주문을 확정할 수 있는가.

**"접수됐다"는 확정할 수 있고, "접수되지 않았다"는 스펙만으로는 확정할 수 없다.**

- 유일 식별값은 없다(A·B절). 조합 키도 유일하지 않다(D절 2·3). 그래도 접수 확정에는 유일성이 필요 없다. 우리가 접수 응답을 받은 주문은 전부 `oder_no`를 갖고 있으므로, 당일 목록에서 **우리가 아는 `oder_no`를 뺀 나머지** 가운데 (종목, 방향, `aspr_type_code`, 수량, 가격)이 같고 원주문(`oder_rctf_cncl_cls_code=0`)이며 접수 시각이 선점 시각 뒤인 행이 후보다. 같은 값의 SUBMITTING 주문이 n개이고 후보가 n개면 하나씩 짝지어 PLACED로 확정한다. 값이 전부 같은 주문끼리는 어느 leg에 어느 `oder_no`를 붙여도 돈의 결과가 같다.
- 후보 수가 SUBMITTING 수와 다르면(0개 포함) 확정하지 않고 지금 동작(관리자 알림, 사람이 처리)으로 둔다. 후보가 0개인 것이 "거절됐다"인지 "아직 목록에 안 보인다"인지 스펙으로는 가를 수 없다(G절 2·3). 그러므로 **이 복구는 재전송을 하지 않는다.** 로드맵 9-2 U1의 기준("접수 여부를 모르면 재전송하지 않는다")과 같다.
- 남는 오판 경로는 하나다: 사용자가 같은 계좌로 증권사 앱에서 같은 종목·방향·유형·수량·가격의 주문을 선점 시각 뒤에 직접 낸 경우. 그 주문을 우리 것으로 붙이게 된다. 드물고, 붙여도 그 계좌의 실제 주문이라 원장이 실계좌와 어긋나지는 않는다.

구현 전에 실계좌로 확인해야 하는 것(실행 세션은 실계좌를 부르지 않는다. 사용자 작업): G절 1(접수 시각의 시간대), 5(당일 목록의 "당일" 경계와 페이지), 7(시장가·MOC 매도의 `aspr_type_code`), 8(가격 문자열 비교). 2·3은 확인하지 못해도 위 설계가 안전한 쪽으로 동작한다(후보가 없으면 아무것도 하지 않는다).

지침서 12의 10절 기본값("식별할 값을 주면 04의 3절 표에 항목으로 더한다")에 따라 `guides/04-broker.md` 3절에 B10으로 더했다. 선행 조건은 위 실측 4건이다. 지금 동작은 바꾸지 않았다.
