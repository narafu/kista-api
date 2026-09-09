package com.kista.user.application.service;

import com.kista.user.application.usecase.TelegramApprovalUseCase;
import com.kista.user.application.usecase.UserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class TelegramApprovalService implements TelegramApprovalUseCase {

    private final UserUseCase userUseCase;

    @Override
    public void handle(Action action, UUID targetUserId) {
        switch (action) {
            case APPROVE -> userUseCase.approve(targetUserId);
            case REJECT -> userUseCase.reject(targetUserId, null); // 텔레그램 인라인 버튼은 사유 입력 UI 없음
        }
    }
}
