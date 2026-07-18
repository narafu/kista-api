package com.kista.adapter.out.kbland;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kbland")
public record KbLandProperties(String baseUrl) {}
