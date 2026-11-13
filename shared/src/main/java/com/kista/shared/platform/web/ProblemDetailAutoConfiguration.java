package com.kista.shared.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

// 공용 예외 advice 등록 — PlatformAutoConfiguration과 따로 두는 이유: @WebMvcTest 슬라이스에도 올려야 하는데
// (META-INF/spring/...AutoConfigureWebMvc.imports) 보안·Redis 등 나머지 platform 빈은 슬라이스에 올리지 않는다
@AutoConfiguration
@Import(ProblemDetailExceptionHandler.class)
public class ProblemDetailAutoConfiguration {
}
