package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.BrokerConnectionTestPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerWithdrawalEnabledException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

// 바이낸스 연결 테스트 — apiRestrictions로 출금 권한·IP 제한·거래/조회 권한 검증 (키·IP 오류는 호출 자체가 -2015/-1022 → BrokerCredentialException)
@Component
@RequiredArgsConstructor
class BinanceAuthApi implements BrokerConnectionTestPort {

    private final BinanceHttpClient http; // 서명·오류 매핑 공통

    @Override
    public Broker supports() {
        return Broker.BINANCE;
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey) {
        checkRestrictions(appKey, secretKey);
    }

    @Override
    public BrokerAccountVerification verifyAccount(String appKey, String secretKey, String accountNo) {
        checkRestrictions(appKey, secretKey); // 바이낸스는 계좌번호·키 만료일이 없다 — accountNo 무시
        return new BrokerAccountVerification(null, null);
    }

    private void checkRestrictions(String appKey, String secretKey) {
        BrokerAccountRef auth = new BrokerAccountRef(null, appKey, secretKey, null, null, Broker.BINANCE);
        ApiRestrictionsRow r = http.getSigned("/sapi/v1/account/apiRestrictions", new LinkedHashMap<>(), auth,
                new ParameterizedTypeReference<ApiRestrictionsRow>() {});
        // 출금은 IP 제한이 있어야 켤 수 있어 출금 판정이 먼저
        if (r.enableWithdrawals()) throw new BrokerWithdrawalEnabledException();
        if (!r.ipRestrict()) throw new BrokerIpNotAllowedException();
        if (!r.enableSpotAndMarginTrading() || !r.enableReading()) throw new BrokerPermissionDeniedException();
    }

    record ApiRestrictionsRow(boolean ipRestrict, boolean enableReading, boolean enableSpotAndMarginTrading, boolean enableWithdrawals) {}
}
