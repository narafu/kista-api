package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.port.out.KisTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class KisTokenAdapter implements KisTokenPort {

    private final KisHttpClient kisHttpClient;

    @Override
    public String getToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", kisHttpClient.props().appKey(),
                "appsecret", kisHttpClient.props().appSecret()
        );

        TokenResponse response = kisHttpClient.post("/oauth2/tokenP", headers, body, TokenResponse.class);
        return response.accessToken();
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}
