package com.kista.web;

import com.kista.platform.internalapi.InternalApiProperties;
import com.kista.platform.telegram.TelegramProperties;
import com.kista.user.adapter.out.kakao.KakaoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

// 필수 환경변수 누락·빈값이 기동 실패로 이어지는지 검증 — deploy-role.sh grep 검사를 앱 기동 검증으로 대체한 근거.
// root에 둔 이유: trading-core는 platform.internalapi 참조가 금지돼 있고(GradleModuleBoundaryTest), :shared 테스트엔 Bean Validation 구현체가 없다
class RequiredPropertiesValidationTest {

    // 정상 값 — 각 케이스는 하나만 빼거나 비운다
    private static final String[] VALID = {
            "telegram.bot-token=bot", "telegram.chat-id=1", "internal.api.token=t", "kakao.client-id=k"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class);

    @Test
    void 모든_값이_있으면_기동한다() {
        runner.withPropertyValues(VALID).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 값이_비어_있으면_기동_실패한다() {
        for (String blank : new String[]{"internal.api.token=", "telegram.bot-token= ", "kakao.client-id="}) {
            runner.withPropertyValues(VALID).withPropertyValues(blank)
                    .run(ctx -> assertThat(ctx).as(blank).hasFailed());
        }
    }

    @Test
    void 키가_없으면_기동_실패한다() {
        runner.withPropertyValues("telegram.bot-token=bot", "internal.api.token=t", "kakao.client-id=k")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 환경변수가_없으면_yml_빈_기본값으로_바인딩돼_기동_실패한다() {
        // application.yml의 ${TELEGRAM_BOT_TOKEN:} — 빈 기본값이 없으면 "${...}" 문자열이 그대로 바인딩돼 @NotBlank를 통과한다
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("internal.api.token=t", "kakao.client-id=k")
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("telegram"));
    }

    @Configuration
    @EnableConfigurationProperties({TelegramProperties.class, InternalApiProperties.class, KakaoProperties.class})
    static class PropsConfig {
    }
}
