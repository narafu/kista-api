// stats 모듈의 공개 계약 일부 — 백테스트 커맨드·결과 도메인 타입(레거시 domain/model/backtest 구조 유지).
// "domain" 이름으로 병합 공개된다(domain/model과 동일 이름 — trading의 model+strategy 병합 공개와 같은 패턴).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.stats.domain.model.backtest;
