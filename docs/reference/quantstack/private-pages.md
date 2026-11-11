# 비공개·비색인 페이지 (/login/, /portfolio/, /portfolio/account/, /feedback/)

- 원본 URL: https://quantstack.app/portfolio/
- 수집일: 2026-11-11 (사이트 표기 마지막 업데이트: 2026-09-14)
- quantstack 자체 계산·해설(원 방법론 규칙 아님)

- sitemap.xml 미포함, robots.txt Disallow(/login/, /portfolio/, /feedback/). 로그인(이메일/비밀번호 6자 이상·카카오), 피드백 폼(종류·내용·이메일 선택), 포트폴리오(로그인 필요; 계좌 이름·전략·종목·분할수·원금·시작일·메모) 정적 셸만 공개. 계좌 상세 `/portfolio/account/?id=…`는 로그인 필요.
- 전략 키: infinite_v4/v3/v22 "무한매수법 V4.0/V3.0/V2.2", vr_install/vr_lump/vr_withdraw "VR 적립식/거치식/인출식", other. 구현 로직은 JS 번들에서 확인해 infinite-buying-v4.md §C, value-rebalancing.md §C에 기록. 사용자 데이터(계좌)는 로그인 뒤라 읽지 않음.
