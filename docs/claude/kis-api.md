## KIS API

- 모든 KIS 호출은 `KisHttpClient` 경유 (공통 헤더: `authorization`, `appkey`, `appsecret`, `tr_id`, `custtype: P`)
- 토큰 관리는 `KisTokenAdapter`만 담당
- Base URL: `https://openapi.koreainvestment.com:9443`

### 응답 필드명 대소문자 주의
- **해외주식 API 응답은 lowercase** (`ovrs_pdno`, `ovrs_cblc_qty`, `frcr_evlu_amt2` 등)
- 국내주식 API는 UPPERCASE — 혼동 주의, `@JsonProperty` 값 반드시 소문자로

### 잔고 조회 API 파라미터
- `TTTS3012R` (해외주식 잔고): `CANO`, `ACNT_PRDT_CD`, `OVRS_EXCG_CD=NASD`(실전 미국전체), `TR_CRCY_CD=USD`, `CTX_AREA_FK200=""`, `CTX_AREA_NK200=""`
- `CTRP6504R` (체결기준현재잔고): `CANO`, `ACNT_PRDT_CD`, `WCRC_FRCR_DVSN_CD=02`, `NATN_CD=000`, `TR_MKET_CD=00`, `INQR_DVSN_CD=00`
- API 파라미터 불확실 시 `kis-coding-mcp`의 `search_overseas_stock_api` + `read_source_code`로 공식 확인

### KIS Adapter 단위 테스트
- Spring 컨텍스트 없이 `@ExtendWith(MockitoExtension.class)` 순수 Mockito 사용
- `KisHttpClient` mock 시 `props()`와 `buildHeaders()` 모두 스텁 필요 (`KisProperties` record는 직접 생성)
- Adapter 내부 `record` (예: `KisOrderAdapter.OrderResponse`)는 같은 패키지 테스트에서 직접 접근 가능
