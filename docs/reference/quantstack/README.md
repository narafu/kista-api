# quantstack.app 정리본 (KISTA 매매 공식 검증용 참고 문헌)

- 수집일: 2026-11-11. 사이트: https://quantstack.app (개인 운영 비공식 해설 사이트, Astro/Starlight 정적 페이지). 동기: 사이트 소멸 대비.
- **중요한 한계**: 이 사이트는 원저작자(라오어)의 원문이 아니라 제3자(quantstack)의 요약·해설이다. 각 페이지는 "규칙 요약"과 "quantstack 해설"로 나뉘며, 검증 근거는 규칙 요약·계산기 로직에 한정하고 해설·사이트 코드 구현은 보조로만 쓴다. 최종 기준은 원저작자 공지.
- 저작권: 사이트는 콘텐츠 복제·전재를 금지(출처 표기 짧은 인용 허용)한다. 이 문서들은 문장을 옮기지 않은 구조화 노트(내부 참조용)다.
- 가장 중요한 문서: **infinite-buying-v4.md**(무한매수법 4.0), **value-rebalancing.md**(VR 5.0).

## 사이트 구조
- 내비게이션: 홈 / 기초(7편) / 가이드(8편) / 분석·칼럼(8편) / 무한매수법(개요, V2.0, V2.1, V2.2, V3.0, V4.0 일반·리버스) / VR 5.0(개요, 용어&공식, 절차, 백테스트, 적립식, 거치식, 인출식, 유형 전환) / 도구(계산기, 내 포트폴리오 🔒) / 소개·약관·개인정보·업데이트 기록.
- sitemap-index.xml → sitemap-0.xml, 47 URL. robots.txt: /login/ /portfolio/ /feedback/ Disallow.
- 다루는 전략: 무한매수법(V2.0, V2.1, V2.2, V3.0, V4.0) 및 VR 5.0뿐. VR V1~V4나 그 밖의 전략은 사이트에 없음.

## URL ↔ 파일 (sitemap 47개 전부 수집)
| URL | 파일 |
|---|---|
| https://quantstack.app/ | home.md |
| https://quantstack.app/about/ | about.md |
| https://quantstack.app/analysis/ | analysis-index.md |
| https://quantstack.app/analysis/backtest-trap/ | analysis-backtest-trap.md |
| https://quantstack.app/analysis/five-misconceptions/ | analysis-five-misconceptions.md |
| https://quantstack.app/analysis/split-buying-math/ | analysis-split-buying-math.md |
| https://quantstack.app/analysis/split-count/ | analysis-split-count.md |
| https://quantstack.app/analysis/tqqq-vs-soxl/ | analysis-tqqq-vs-soxl.md |
| https://quantstack.app/analysis/version-evolution/ | analysis-version-evolution.md |
| https://quantstack.app/analysis/volatility-decay/ | analysis-volatility-decay.md |
| https://quantstack.app/analysis/vr-parameters/ | analysis-vr-parameters.md |
| https://quantstack.app/basics/ | basics-index.md |
| https://quantstack.app/basics/fx-effect/ | basics-fx-effect.md |
| https://quantstack.app/basics/how-leveraged-etf-works/ | basics-how-leveraged-etf-works.md |
| https://quantstack.app/basics/leverage-multiple-choice/ | basics-leverage-multiple-choice.md |
| https://quantstack.app/basics/loss-recovery/ | basics-loss-recovery.md |
| https://quantstack.app/basics/trading-journal/ | basics-trading-journal.md |
| https://quantstack.app/basics/us-market-sessions/ | basics-us-market-sessions.md |
| https://quantstack.app/basics/variance-and-decay/ | basics-variance-and-decay.md |
| https://quantstack.app/changelog/ | changelog.md |
| https://quantstack.app/guide/ | guide-index.md |
| https://quantstack.app/guide/capital-planning/ | guide-capital-planning.md |
| https://quantstack.app/guide/choose-version/ | guide-choose-version.md |
| https://quantstack.app/guide/costs-and-tax/ | guide-costs-and-tax.md |
| https://quantstack.app/guide/faq/ | guide-faq.md |
| https://quantstack.app/guide/glossary/ | guide-glossary.md |
| https://quantstack.app/guide/infinite-vs-vr/ | guide-infinite-vs-vr.md |
| https://quantstack.app/guide/loc-orders/ | guide-loc-orders.md |
| https://quantstack.app/guide/mistakes/ | guide-mistakes.md |
| https://quantstack.app/infinite/ | infinite-index.md |
| https://quantstack.app/infinite/v2-0/ | infinite-v2-0.md |
| https://quantstack.app/infinite/v2-1/ | infinite-v2-1.md |
| https://quantstack.app/infinite/v2-2/ | infinite-v2-2.md |
| https://quantstack.app/infinite/v3-0/ | infinite-v3-0.md |
| https://quantstack.app/infinite/v4-0-normal/ | infinite-buying-v4.md |
| https://quantstack.app/infinite/v4-0-reverse/ | infinite-buying-v4.md |
| https://quantstack.app/privacy/ | privacy.md |
| https://quantstack.app/terms/ | terms.md |
| https://quantstack.app/tools/calculator/ | tools-calculator.md (+infinite-buying-v4.md §B) |
| https://quantstack.app/vr/ | vr-index.md |
| https://quantstack.app/vr/backtest/ | vr-backtest.md |
| https://quantstack.app/vr/convert/ | vr-convert.md |
| https://quantstack.app/vr/installment/ | vr-installment.md |
| https://quantstack.app/vr/lumpsum/ | vr-lumpsum.md |
| https://quantstack.app/vr/overview/ | vr-overview.md |
| https://quantstack.app/vr/procedure/ | vr-procedure.md |
| https://quantstack.app/vr/withdrawal/ | vr-withdrawal.md |
| https://quantstack.app/login/ , /portfolio/ , /portfolio/account/ , /feedback/ (sitemap 밖, robots Disallow) | private-pages.md |

## 수집 방법과 대조
- 모든 페이지를 HTML로 받아 본문(`<main>`)을 텍스트 변환(표, callout 포함; `~` 범위 표기 복원). 이미지·내용이 있는 SVG 없음(본문 이미지 0개).
- 사이트 내부 링크 전체(href)를 sitemap과 대조: sitemap 외 내부 링크는 /feedback/, /portfolio/ 뿐(→ private-pages.md). 계산기·포트폴리오 JS 번들(`calc`, `Calculator`, `portfolio`, `AccountDetailPage`)도 읽어 공식·주문 생성 로직을 추출.

## 읽지 못한 것 / 한계
- 로그인 뒤 데이터(포트폴리오 계좌 내용): 접근 불가·불필요. 화면 로직은 JS 번들로 확인했으나 서버(Supabase) 측 로직 유무는 알 수 없음.
- 포트폴리오 "오늘의 주문" 로직은 공개 번들에서 읽었으나 사이트 운영자의 해석 구현이며, 리버스모드는 구현되어 있지 않음.
- 사이트에 없는 정보(원문 공지, VR V1~V4, 2주 주문표의 가격 간격 산식, 30분할 별% 등)는 추측으로 채우지 않았고 각 파일의 "불명확" 절에 기록.
- 페이지에 실린 운영자 이메일은 정리본에서 제외.
- 사이트 내 모순 요약: infinite-buying-v4.md §E, infinite-v3-0.md, guide-choose-version.md, analysis-version-evolution.md, guide-faq.md(주간장 정의), guide-loc-orders.md vs basics-us-market-sessions.md(LOC 마감 시한).
