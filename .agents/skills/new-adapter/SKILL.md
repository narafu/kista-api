---
name: new-adapter
description: kista-api에 신규 외부 서비스 어댑터(REST 클라이언트) 추가 시 구조 패턴 — KakaoConfig/TelegramConfig/AlpacaConfig와 동일한 3파일 구성
---

# 신규 외부 서비스 어댑터 구조 패턴

`adapter/out/<서비스명>/` 아래 3파일로 구성 (KakaoConfig, TelegramConfig, AlpacaConfig 동일 패턴):

- `*Properties.java` — `@ConfigurationProperties(prefix="...")` record
- `*Config.java` — `@Configuration` + `@EnableConfigurationProperties(*Properties.class)` + RestTemplate `@Bean`
- `*Adapter.java` — `@Component`, Port 구현, RestTemplate + Properties 주입
