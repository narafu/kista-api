package com.kista;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// application-prod.yml = API role 기본값.
// 스케쥴러 off + Modulith 이벤트 재발행 off 두 값이 실수로 지워지면
// (1) API 컨테이너가 스케쥴러를 돌려 운영 DB·텔레그램 중복 실행
// (2) API·스케쥴러 양쪽이 event_publication 미완료 행을 재발행해 새벽 알림 2번
// 이 두 사고를 막는 회귀 테스트. 컨텍스트 로드 없이 yaml만 파싱한다.
class SchedulerRoleConfigTest {

    private Properties prodYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        return props;
    }

    @Test
    void prod_disablesScheduler() {
        assertThat(prodYaml().getProperty("scheduler.enabled")).isEqualTo("false");
    }

    @Test
    void prod_disablesModulithEventRepublish() {
        assertThat(prodYaml().getProperty("spring.modulith.events.republish-outstanding-events-on-restart"))
                .isEqualTo("false");
    }
}
