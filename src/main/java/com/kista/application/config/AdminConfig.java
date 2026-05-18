package com.kista.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// AdminBootstrapProperties @ConfigurationProperties 활성화
@Configuration
@EnableConfigurationProperties(AdminBootstrapProperties.class)
class AdminConfig {}
