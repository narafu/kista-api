// 전역 인프라 leaf — persistence base entity, 대칭키 암호화, 스케쥴러 공통 골격.
// Spring/JPA 바인딩이 있어 com.kista.common(순수 유틸)과 분리한다. Type.OPEN이되
// HexagonalArchitectureTest.platform_must_not_depend_on_other_modules가 outbound-zero(→common만 허용)를 강제한다
// — sharedkernel과 동일하게 "OPEN은 outbound-zero를 증명할 때만 안전" 원칙.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.platform;
