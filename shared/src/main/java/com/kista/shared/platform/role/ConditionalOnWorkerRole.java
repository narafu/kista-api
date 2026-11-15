package com.kista.shared.platform.role;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// kista.role이 worker·all일 때만 빈 등록 — @Scheduled·수동 트리거 컨트롤러 등 배치 전용 빈의 게이트
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(WorkerRoleCondition.class)
public @interface ConditionalOnWorkerRole {
}
