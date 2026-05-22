package com.kista.adapter.out.notify;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
class FcmConfig {

    @Bean
    FirebaseMessaging firebaseMessaging(
            @Value("${firebase.service-account-json:}") String serviceAccountJson) throws IOException {
        // 환경변수 미설정 시 FCM 비활성화
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("firebase.service-account-json 미설정 — FCM 알림 비활성화");
            return null;
        }
        if (FirebaseApp.getApps().isEmpty()) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
        }
        return FirebaseMessaging.getInstance();
    }
}
