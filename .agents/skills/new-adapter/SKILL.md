---
name: new-adapter
description: kista-api에 신규 외부 서비스 어댑터(REST 클라이언트) 추가 시 구조 패턴 — KakaoConfig/AlpacaConfig와 동일한 3파일 구성 (RestClient 기반)
---

# 신규 외부 서비스 어댑터 구조 패턴

소유 모듈의 `adapter/out/<서비스명>/` 아래 3파일로 구성 (`user/adapter/out/kakao`의 KakaoConfig, `benchmark`·`marketcalendar`·`tradingstats`의 AlpacaConfig 동일 패턴):

- `*Properties.java` — `@ConfigurationProperties(prefix="...")` record
- `*Config.java` — `@Configuration` + `@EnableConfigurationProperties(*Properties.class)` + `RestClient` `@Bean` (`RestTemplate` 아님). 타임아웃·baseUrl은 `com.kista.platform.http.RestClients.withTimeouts(...)` 재사용
- `*Adapter.java` — `@Component`, Port 구현, `RestClient` + Properties 주입

주의:
- `RestClient` 빈이 여러 개이므로 빈 이름을 서비스별로 고유하게(`kakaoRestClient`, `marketAlpacaRestClient` 등) 짓고 주입 필드명을 빈 이름과 일치시킨다 — 불일치 시 `NoUniqueBeanDefinitionException`. 다른 모듈에 같은 클래스명(`AlpacaConfig`)이 있으면 `@Configuration("...")`으로 이름도 구분한다
- 프로세스 공용 인프라(예: 텔레그램 `com.kista.platform.telegram.TelegramConfig`)는 `:shared`의 `platform`에 두고 어댑터 대신 클라이언트 클래스(`TelegramHttpClient`)를 노출한다
