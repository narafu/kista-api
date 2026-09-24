package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;

import java.util.NoSuchElementException;
import java.util.UUID;

// 매매 알림 4종(CycleEndedNotifier/CycleLifecycleNotifier/TradingReportNotifier/TradingAlertNotifier)의
// requireProfile 중복 제거용 공용 헬퍼 — TradingUserProfilePort에 default 메서드로 두면 Mockito mock이
// override해 기존 stub 테스트가 깨지므로 일반 static 메서드로 분리
final class TradingUserProfiles {

    private TradingUserProfiles() {
    }

    // ID → TradingUserProfile 재조회 (EPR 역직렬화 대응) — root UserPort.findByIdOrThrow 대체
    static TradingUserProfile requireProfile(TradingUserProfilePort userProfilePort, UUID userId) {
        return userProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("user_notify_profile 없음: " + userId));
    }
}
