# 서울 아파트 벤치마크 성과 비교 설계

날짜: 2026-07-19
범위: `kista-api` + `kista-ui`
화면: 통계 페이지의 `벤치마크 비교` 탭

## 목적

사용자가 자신의 투자 성과를 SPY 같은 시장 벤치마크와 비교하듯 서울 아파트 가격 상승률과 비교한다. 월별 승패나 상회 횟수보다 동일 기간의 누적 성장 경로와 장기 초과 성과를 보여주는 것이 목적이다.

첫 화면은 전체 포트폴리오와 서울 아파트 3분위를 최근 5년간 비교한다. 사용자는 개별 전략, 서울 1~5분위, 비교 기간을 변경할 수 있다.

## 제품 결정

- 통계 페이지 안에 `운용 통계`와 `벤치마크 비교` 탭을 둔다.
- 기본 대상은 `전체 포트폴리오`, 기본 벤치마크는 `서울 3분위`, 기본 기간은 `5년`이다.
- 선택 가능한 기간은 `1년`, `3년`, `5년`, `전체`다.
- 포트폴리오와 벤치마크를 첫 공통 월의 지수 100으로 맞춰 한 차트에 표시한다.
- 핵심 수치는 투자 누적 수익률, 아파트 누적 상승률, 두 값의 차이인 초과 성과다.
- 투자 성과는 월말 USD/KRW 매매기준율을 반영한 원화 기준으로 표시한다.
- 보조 지표는 연평균 수익률과 최대 낙폭이다.
- 월별 승패, 상회 횟수, 연속 상회·하회는 제공하지 않는다.
- 분위별 대표 지역·단지 설명은 선택기 옆 정보 영역에 두며 차트보다 낮은 시각적 우선순위를 갖는다.

## 데이터 기준

### 서울 아파트

- 데이터 소스: `housing_benchmark_prices`
- 지표: `APT_QTE_SALE_PRICE`
- 지역: 서울 `1100000000`
- 주기: 월별
- 선택 분위에 따라 `first_quintile_price`부터 `fifth_quintile_price`까지 하나를 사용한다.
- 가격 단위는 수익률 계산에서 소거되므로 원 단위 변환 없이 원본 값을 사용한다.
- 동일 분위의 전월 값이 없거나 0 이하이면 해당 월 수익률을 계산하지 않는다.

### 투자 자산

- 평가값: `cycle_position.usd_deposit + holdings * closing_price`
- 날짜 기준: `cycle_position.created_at`을 KST 날짜로 변환한다.
- 하루에 같은 사이클 스냅샷이 여러 건이면 마지막 값을 사용한다.
- 스냅샷이 없는 날은 직전 값을 이어 사용한다.
- 종료된 사이클은 종료일까지만 포함하고 이후에는 제외한다.
- 전체 포트폴리오는 사용자가 소유한 모든 전략을 합산한다.
- 개별 전략은 소유권을 확인한 `strategyId` 하나만 집계한다.

## 수익률 계산

### 투자 수익률

단순 자산 증감률은 신규 시드 투입과 회수를 수익으로 오인하므로 사용하지 않는다. 일별 평가액과 외부 자금 흐름을 이용해 일별 시간가중수익률을 만든 뒤 월 단위로 복리 연결한다.

```text
일별 수익률 r(d) = V(d) / (V(d-1) + F(d)) - 1
월간 수익률 R(m) = product(1 + r(d)) - 1
투자 누적 지수 I(m) = I(m-1) * (1 + R(m))
```

- `V(d)`: 대상 범위의 일말 전략 운용 자산
- `F(d)`: 해당 일의 외부 순유입. 유입은 양수, 회수는 음수
- 첫 전략·첫 사이클 시작금액은 외부 유입이다.
- 같은 전략의 사이클 교체는 이전 `endAmount`와 다음 `startAmount`의 차이만 외부 흐름으로 본다.
- 다음 사이클 시드가 이전 종료금액과 같으면 전액 재투자이므로 흐름은 0이다.
- 다음 시드가 더 작으면 차액은 회수, 더 크면 차액은 추가 투입이다.
- 종료 후 다음 사이클이 없는 전략은 전체 포트폴리오 계산에서 종료금액을 회수로 처리한다.
- 개별 전략의 마지막 사이클 종료 후에는 시계열을 종료한다.
- 분모가 0 이하이거나 필요한 평가값이 없으면 해당 일 수익률을 만들지 않는다.

월별 포인트는 해당 월의 마지막 유효 일별 누적 지수를 사용한다. 이 방식은 사이클 수익 재투자를 보존하면서 전략 추가·종료로 인한 자산 규모 변화가 성과로 계산되는 것을 막는다.

### USD/KRW 환율

- 데이터 소스: 토스증권 공통 API `GET /api/v1/exchange-rate`
- 인증: 기존 관리자 OAuth 2.0 Client Credentials와 `TossHttpClient.getCommon()`을 재사용한다.
- 요청: `baseCurrency=USD`, `quoteCurrency=KRW`, `dateTime=<월말 기준시각>`
- 적용값: 환전 스프레드가 포함된 매수 환율 `rate`가 아니라 매매기준율 `midRate`
- 저장 주기: 월별
- 월말이 비영업일이거나 지정 시각 데이터가 없으면 해당 월 안에서 이전 날짜로 최대 7일까지 역순 재시도한다.
- 저장된 실제 관측일을 함께 보관해 어떤 환율이 적용됐는지 추적한다.

USD 기준 투자 누적 지수에 기준 월 대비 환율 변화를 적용해 원화 기준 지수를 만든다.

```text
원화 투자 지수 I(krw,m) = I(usd,m) * FX(m) / FX(base)
```

`FX(base)`와 `FX(m)`은 각 월의 마지막 유효 매매기준율이다. 필요한 월의 환율이 없으면 해당 월 이후를 임의 보간하지 않고 공통 구간에서 제외한다.

### 아파트 상승률

```text
월간 상승률 H(m) = price(m) / price(m-1) - 1
아파트 누적 지수 B(m) = B(m-1) * (1 + H(m))
```

### 공통 구간과 요약 지표

- 투자와 아파트 데이터가 모두 존재하는 첫 월부터 마지막 공통 월까지만 반환한다.
- 첫 공통 월은 투자 지수와 아파트 지수 모두 100이다.
- 누적 수익률: `마지막 지수 / 100 - 1`
- 초과 성과: `투자 누적 수익률 - 아파트 누적 상승률`
- 연평균 수익률: `(마지막 지수 / 100)^(12 / 경과 개월 수) - 1`
- 최대 낙폭: 월별 지수의 이전 최고점 대비 최대 하락률
- 비교 가능한 공통 월이 2개 미만이면 요약 지표를 반환하지 않고 빈 상태 사유를 제공한다.

## 통화와 정확도 정책

서울 아파트가 원화 자산이므로 투자 성과도 원화 기준으로 비교한다. 토스증권 공통 API의 특정 시점 환율 조회를 사용해 월말 USD/KRW 매매기준율을 저장하고 환율 효과를 포함한다. UI에는 `투자 성과는 월말 USD/KRW 매매기준율을 반영한 원화 기준입니다.`를 표시한다.

토스증권 공식 문서는 `dateTime`으로 특정 시점 환율을 조회할 수 있음을 명시하지만 과거 조회 가능 기간과 호출 제한을 구체적으로 공개하지 않는다. 환율 검증과 backfill 범위는 전체 API 이력이 아니라 가장 이른 전략 월부터 KB 서울 데이터와 겹치는 실제 필요 구간으로 제한한다. 현재 로컬 전략 데이터는 2026-06-16부터 시작하므로 최초 필요 월은 2026년 6월이다. 스파이크 테스트에서 2023년 환율은 조회됐고 2020년 환율은 `exchange-rate-not-found`로 조회되지 않았지만, 2020년은 실제 필요 교집합 밖이므로 구현 중단 조건이 아니다. 실제 필요 교집합의 월 중 하나라도 토스에서 조회할 수 없을 때만 구현을 중단하고 대체 환율 소스를 선택한다. 검증되지 않은 환율로 차트를 제공하지 않는다.

기존 데이터의 추가 한계는 다음과 같다.

- 과거 `startAmount` 수정은 이력 테이블이 없어 수정 전 자금 흐름을 복원할 수 없다.
- 포지션 스냅샷이 생성되지 않은 기간은 직전 평가값을 이어 사용하므로 실제 일별 변동보다 평활화될 수 있다.
- 수수료와 세금은 별도로 차감하지 않으며 포지션 스냅샷에 반영된 잔고만 사용한다.

UI에서는 결과를 `전략 운용 기록 기반 근사치`로 표시한다. 새 자금 흐름 테이블은 이번 범위에 추가하지 않는다. 현 데이터로 계산 불가능한 왜곡이 테스트에서 확인되면 구현을 중단하고 자금 흐름 원장 설계를 별도 범위로 분리한다.

## API 설계

### 엔드포인트

```http
GET /api/stats/housing-benchmark
```

쿼리 파라미터:

| 이름 | 형식 | 기본값 | 규칙 |
|---|---|---|---|
| `scope` | `PORTFOLIO` 또는 `STRATEGY` | `PORTFOLIO` | 비교할 투자 범위 |
| `strategyId` | UUID | 없음 | `scope=STRATEGY`이면 필수, 사용자 소유권 검증 |
| `quintile` | 1~5 | 3 | 서울 아파트 분위 |
| `from` | `YYYY-MM-DD` | 없음 | 요청 기간 시작, 서버가 월 첫날로 정규화 |
| `to` | `YYYY-MM-DD` | 오늘 | 요청 기간 종료, 서버가 해당 월로 정규화 |

기간 프리셋은 UI가 `from`과 `to`로 변환한다. `전체`는 `from`을 보내지 않는다.

### 응답

```json
{
  "scope": "PORTFOLIO",
  "strategy": null,
  "benchmark": {
    "regionCode": "1100000000",
    "regionName": "서울",
    "quintile": 3,
    "label": "서울 아파트 3분위",
    "sourceUpdatedDate": "2026-06-15"
  },
  "period": {
    "fromMonth": "2021-06-01",
    "toMonth": "2026-06-01",
    "monthCount": 61
  },
  "summary": {
    "investmentCumulativeReturn": 0.842,
    "benchmarkCumulativeReturn": 0.517,
    "excessReturn": 0.325,
    "investmentAnnualizedReturn": 0.127,
    "benchmarkAnnualizedReturn": 0.088,
    "investmentMaxDrawdown": -0.184,
    "benchmarkMaxDrawdown": -0.032
  },
  "points": [
    {
      "baseMonth": "2021-06-01",
      "investmentIndexKrw": 100.0,
      "benchmarkIndex": 100.0,
      "usdKrwMidRate": 1365.2,
      "exchangeRateObservedDate": "2021-06-30",
      "investmentMonthlyReturn": null,
      "benchmarkMonthlyReturn": null
    }
  ],
  "quality": {
    "method": "ESTIMATED_TIME_WEIGHTED_RETURN",
    "currencyBasis": "KRW",
    "exchangeRateSource": "TOSS_INVEST",
    "notice": "투자 성과는 월말 USD/KRW 매매기준율을 반영한 원화 기준입니다."
  }
}
```

- 비율은 퍼센트가 아닌 소수 비율로 반환한다. UI가 `0.325`를 `+32.5%p`로 표시한다.
- 서버가 정규화와 요약 계산의 단일 기준이다. UI에서 수익률을 재계산하지 않는다.
- `strategy`는 개별 전략일 때 `id`, `type`, `ticker`를 포함한다.
- 데이터가 부족하면 HTTP 200과 빈 `points`, null `summary`, 기계 판독 가능한 `emptyReason`을 반환한다.
- 잘못된 분위·기간·scope 조합은 400, 소유하지 않은 전략은 403으로 처리한다.

## API 내부 구조

```text
domain/model/stats/
  HousingBenchmarkComparison
  HousingBenchmarkPoint
  PerformanceComparisonSummary

domain/port/in/
  UserStatsUseCase.getHousingBenchmarkComparison(...)

domain/port/out/
  HousingBenchmarkPricePort
  MonthlyExchangeRatePort
  HistoricalExchangeRateFeedPort
  CyclePositionPort
  StrategyCyclePort

application/service/stats/
  StatsService                       요청 조율과 소유권 확인
  MonthlyReturnCalculator           일별 TWR, 월별 복리, MDD/CAGR 순수 계산
  HousingBenchmarkComparisonBuilder 공통 월 정렬과 응답 모델 조립

application/service/market/
  MonthlyExchangeRateService        누락 월 조회·저장과 월말 영업일 fallback

adapter/out/toss/
  TossHistoricalExchangeRateAdapter dateTime 지정 환율 조회

adapter/out/persistence/exchangerate/
  MonthlyExchangeRateEntity
  MonthlyExchangeRateJpaRepository
  MonthlyExchangeRatePersistenceAdapter

adapter/in/web/
  StatsController
  HousingBenchmarkComparisonResponse
```

- 기존 `HousingBenchmarkPricePort`의 기간 조회를 재사용한다.
- 전략별·사용자별 포지션 범위 조회는 기존 포트를 우선 재사용하고, 필요한 경우 도메인 포트에 목적이 드러나는 조회 메서드를 추가한다.
- 계산기는 Spring과 JPA에 의존하지 않는 package-private 순수 클래스로 둔다.
- 신규 테이블 `monthly_exchange_rates`를 추가한다.

```text
monthly_exchange_rates
- id UUID PK
- source VARCHAR(20) NOT NULL
- base_currency VARCHAR(3) NOT NULL
- quote_currency VARCHAR(3) NOT NULL
- base_month DATE NOT NULL
- observed_date DATE NOT NULL
- mid_rate NUMERIC(18,6) NOT NULL
- fetched_at TIMESTAMPTZ NOT NULL
- created_at TIMESTAMPTZ NOT NULL
- updated_at TIMESTAMPTZ NOT NULL
- UNIQUE(source, base_currency, quote_currency, base_month)
```

- 비교 API는 저장된 환율만 읽고 외부 API를 호출하지 않는다.
- KB Land 스케줄러 실행 후 같은 기준 월의 환율을 수집한다.
- 최초 배포 시 가장 이른 전략 월부터 시작해 KB 서울 데이터와 투자 기록이 겹치는 실제 필요 월만 대상으로 별도 backfill use case를 실행한다.
- backfill은 월별 독립 트랜잭션과 요청 간 짧은 지연을 사용하고, 실패 월을 건너뛴 뒤 재실행 시 누락 월만 보충한다.
- Flyway 버전은 구현 시 저장소의 최신 번호 다음으로 결정한다.

## UI 설계

### 위치와 탐색

`app/(main)/stats/page.tsx`는 기존대로 통계 페이지를 유지한다. `StatsOverview` 상단에 다음 탭을 추가한다.

```text
[운용 통계] [벤치마크 비교]
```

- `운용 통계`: 기존 KPI, 누적 자산 추이, 전략 유형 비교, 사이클 성과를 그대로 표시한다.
- `벤치마크 비교`: 승인된 목업을 표시한다.
- 탭은 클라이언트 상태로 전환하며 별도 URL은 만들지 않는다.
- 벤치마크 API는 해당 탭을 처음 열 때 조회한다.

### 벤치마크 비교 구성

1. `전체 포트폴리오 / 개별 전략` 세그먼트
2. 개별 전략 선택기. 전략 타입과 종목을 함께 표시
3. 서울 1~5분위 선택기
4. `1년 / 3년 / 5년 / 전체` 기간 선택기
5. 초과 성과, 투자 누적 수익률, 아파트 누적 상승률
6. 시작점 100의 월별 누적 성과 선 차트
7. 누적 수익률, 연평균 수익률, 최대 낙폭 비교표
8. 분위 설명과 데이터 출처·업데이트일·환율 적용 안내

분위 설명은 UI 상수로 관리한다. `대표 지역`이 공식 분류처럼 보이지 않도록 `해당 가격대에서 자주 언급되는 지역·단지 예시`라고 표현하고 다음 문구를 항상 노출한다.

> 지역과 단지는 가격 구간을 이해하기 위한 예시입니다. KB 5분위 통계는 개별 아파트 가격을 기준으로 구분되며, 특정 지역이나 단지가 하나의 분위에 고정적으로 포함되는 것은 아닙니다.

### UI 코드 배치

```text
entities/stats/
  model/types.ts                     API 응답 타입 추가
  api/index.ts                       getHousingBenchmarkComparison
  hooks/useStatsQueries.ts           조건부 query hook

widgets/stats-overview/
  StatsOverview.tsx                  탭 상태와 섹션 전환
  HousingBenchmarkComparison.tsx     조회 상태와 화면 조합
  HousingBenchmarkChart.tsx          Recharts 월별 누적 성과 차트
  HousingBenchmarkSummary.tsx        핵심 수치와 비교표
  HousingBenchmarkInfo.tsx           분위 설명과 주의사항
```

- 기존 Recharts, 카드, 토글, 포맷 유틸을 재사용한다.
- 차트 데이터는 API의 정규화 지수를 그대로 사용한다.
- 모바일에서는 선택기를 세로로 재배치하고 핵심 수치 3개를 `초과 성과` 1행 + 나머지 2열로 표시한다.
- 비교표는 모바일에서도 가로 스크롤 없이 지표·투자·아파트 3열을 유지한다.
- 양수·음수는 색상뿐 아니라 `+`/`-` 부호로 구분한다.

## 로딩, 오류, 빈 상태

- 탭 최초 진입: 차트와 요약 영역 크기를 유지하는 스켈레톤을 표시한다.
- 필터 변경: 이전 데이터를 유지하면서 컨트롤에 갱신 상태만 표시한다.
- API 오류: 벤치마크 탭 안에서만 `SectionError`를 표시하며 기존 운용 통계에는 영향을 주지 않는다.
- 투자 데이터 없음: `선택한 기간에 전략 운용 기록이 없습니다.`
- 공통 월 부족: `투자 기록과 서울 아파트 데이터가 겹치는 기간이 부족합니다.`
- KB 데이터 최신 월이 요청 종료 월보다 이전이면 마지막 제공 월까지만 차트를 그리고 업데이트일을 표시한다.

## 테스트

### kista-api

- `MonthlyReturnCalculatorTest`
  - 추가 시드가 수익률로 계산되지 않음
  - 사이클 수익 전액 재투자 시 흐름 0
  - 일부 회수와 추가 투입 처리
  - 동시에 여러 전략이 시작·종료되는 포트폴리오
  - 월 경계, 누락 스냅샷 carry-forward
  - 누적 수익률, CAGR, 최대 낙폭
- `TossHistoricalExchangeRateAdapterTest`
  - `dateTime`, USD/KRW 파라미터와 `midRate` 매핑
  - 비영업일 이전 날짜 fallback
- `MonthlyExchangeRateServiceTest`
  - 저장분 재사용, 누락 월 upsert, 부분 실패 후 재실행
- `StatsServiceTest`
  - 전체 포트폴리오와 개별 전략 범위
  - 전략 소유권 검증
  - KB 분위 컬럼 선택과 공통 월 교집합
  - 데이터 부족 응답
- `StatsControllerTest`
  - 기본 파라미터, 잘못된 파라미터, 인증 사용자 전달, 응답 매핑
- `./gradlew compileJava`
- 관련 단위 테스트와 `./gradlew test --tests 'com.kista.architecture.*'`

### kista-ui

- API 함수와 React Query 키에 scope, strategyId, quintile, 기간이 모두 반영됨
- 기본값이 전체 포트폴리오·3분위·5년임
- 개별 전략 전환 시 전략 선택기가 나타나고 소유 전략을 조회함
- 필터 변경, 로딩, 오류, 빈 상태 렌더링
- 차트가 API 지수를 재계산하지 않고 표시함
- 분위 설명과 통화 주의사항 노출
- 원화 기준과 환율 출처·관측일 안내 노출
- 기존 운용 통계 탭 회귀 테스트
- `npm run typecheck`, 관련 Vitest, 데스크톱·375px Playwright 스크린샷

## 구현 순서

1. 토스 과거 환율 스파이크 테스트로 조회 범위와 응답 검증
2. `kista-api`: 환율 마이그레이션·어댑터·backfill
3. `kista-api`: 계산기 테스트와 도메인 모델
4. `kista-api`: 포트 조회 확장, 서비스, 컨트롤러 DTO
5. API 테스트와 OpenAPI 확인
6. `kista-ui`: OpenAPI 타입 갱신, stats entity API·hook
7. `kista-ui`: 통계 탭과 벤치마크 위젯
8. 양쪽 테스트, 타입 검사, 반응형 시각 검증

## 제외 범위

- 대시보드 요약 카드
- SPY·QQQ 등 주식 지수 벤치마크 복원
- 월별 승패와 상회 횟수
- 원화/달러 표시 통화 전환 기능
- 대표 지역·단지의 자동 갱신 또는 DB 관리
- 새로운 월별 성과 스냅샷·자금 흐름 테이블
