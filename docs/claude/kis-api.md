## KIS API

- 모든 KIS 호출은 `KisHttpClient` 경유 (공통 헤더: `authorization`, `appkey`, `appsecret`, `tr_id`, `custtype: P`)
- 토큰 관리는 `KisTokenAdapter`만 담당
- Base URL: `https://openapi.koreainvestment.com:9443`

### 응답 필드명 대소문자 주의
- **해외주식 API 응답은 lowercase** (`ovrs_pdno`, `ovrs_cblc_qty`, `frcr_evlu_amt2` 등)
- 국내주식 API는 UPPERCASE — 혼동 주의, `@JsonProperty` 값 반드시 소문자로

### 잔고 조회 API 파라미터
- `TTTS3012R` (해외주식 잔고): `CANO`, `ACNT_PRDT_CD`, `OVRS_EXCG_CD=NASD`(실전 미국전체), `TR_CRCY_CD=USD`, `CTX_AREA_FK200=""`, `CTX_AREA_NK200=""`
- `TTTC2101R` (해외증거금 통화별조회): `CANO`, `ACNT_PRDT_CD` — 응답 `output` 목록에서 `natn_name=="미국"` 행의 `itgr_ord_psbl_amt`를 `usdDeposit`으로 사용
  - `frcr_dncl_amt_2`(환전된 외화만) 대신 `itgr_ord_psbl_amt`(통합주문가능금액) 사용 — 원화 자동 환전 포함
- API 파라미터 불확실 시 `kis-coding-mcp`의 `search_overseas_stock_api` + `read_source_code`로 공식 확인

### 주문 API (KisOrderAdapter)
- 미국 매수 TR ID: `TTTT1002U`, 미국 매도: `TTTT1006U` (일본은 TTTS0308U/0307U — 혼동 주의)
- `ORD_DVSN` 코드: LOC(장마감지정가)=`34`, MOC(장마감시장가)=`33`, LOO(장개시지정가)=`32`, 지정가=`00`
- LOC/MOC 주문 시 `OVRS_ORD_UNPR="0"` 입력

### 체결 조회 API (TTTS3035R, KisExecutionAdapter)
- 파라미터: `CANO`, `ACNT_PRDT_CD`, `PDNO="%"`, `ORD_STRT_DT`, `ORD_END_DT` (INQR_STRT/END_DT 아님!)
- 추가 필수 파라미터: `SLL_BUY_DVSN=00`, `CCLD_NCCS_DVSN=00`, `OVRS_EXCG_CD=NASD`, `SORT_SQN=DS`, `ORD_DT=""`, `ORD_GNO_BRNO=""`, `ODNO=""`
- 응답 lowercase 필드: `pdno`, `sll_buy_dvsn_cd`(01=매도,02=매수), `ft_ccld_qty`, `ft_ccld_unpr3`, `ft_ccld_amt3`, `odno`

### kis-trade-mcp (localhost:3001)
- `open-trading-api/MCP/Kis Trading MCP` 소스, SSE 모드 Docker 컨테이너
- docker run 시 반드시 KIS 자격증명 환경변수 전달 필요 (미전달 시 `ka._TRENV.my_acct` AttributeError)
  - `KIS_APP_KEY`, `KIS_APP_SECRET`, `KIS_HTS_ID`, `KIS_ACCT_STOCK` (kista `.env`의 `KIS_ACCOUNT_NO` 값)
  - `KIS_ACCOUNT_NO` ≠ `KIS_ACCT_STOCK` — 변수명 다름 주의
- `KeyError: 'my_acct'` 오류 시: `docker exec kis-trade-mcp grep -E "my_acct|my_prod" /root/KIS/config/kis_devlp.yaml` 확인
  - `my_acct` 키 없으면: `sed -i '/my_acct_stock:/a my_acct: ...'`로 추가
  - `my_prod: ''`(빈값)이면 `'01'`로 수정 — `changeTREnv()`가 `product=="01"` 분기에서만 계좌번호 설정함
  - 영구 수정: `module/plugin/kis.py` 패치 후 컨테이너 재빌드 (이미 패치됨)
- fastmcp 3.2.4 호환: `server.py` `stateless_http` 제거, `middleware.py`·`tools/base.py` `set_state`/`get_state` 모두 `await` 필요

### KIS Adapter 단위 테스트
- Spring 컨텍스트 없이 `@ExtendWith(MockitoExtension.class)` 순수 Mockito 사용
- `KisHttpClient` mock 시 `props()`와 `buildHeaders()` 모두 스텁 필요 (`KisProperties` record는 직접 생성)
- Adapter 내부 `record` (예: `KisOrderAdapter.OrderResponse`)는 같은 패키지 테스트에서 직접 접근 가능
