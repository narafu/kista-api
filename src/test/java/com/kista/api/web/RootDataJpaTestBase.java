package com.kista.api.web;

import com.kista.shared.support.DataJpaTestBase;

// root 소유 persistence 테스트 공통 베이스 — @DataJpaTest 상향 탐색이 ApiApplication(루트 com.kista.api)을 찾아
// 엔티티·리포지토리 스캔이 자동으로 root 범위로 좁혀지므로, 테스트 classpath의 :trading-core 엔티티는 검증 대상이 아니다.
// root persistence 테스트가 공용 DataJpaTestBase 대신 이 클래스를 상속하는 경로를 유지하려고 남긴다
public abstract class RootDataJpaTestBase extends DataJpaTestBase {
}
