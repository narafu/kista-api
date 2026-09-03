// 점진 이전 중 임시 개방 — Spring Modulith가 이 최상위 패키지를 모듈로 인식할 때
// 다른 애그리게이트 모듈이 이 안의 타입을 참조할 수 있도록 허용한다. 내용물이 모두
// 다른 모듈로 이전되어 비면 이 파일과 함께 자연 소멸시킨다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.adapter;
