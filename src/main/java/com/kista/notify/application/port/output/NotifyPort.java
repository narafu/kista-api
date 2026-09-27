package com.kista.notify.application.port.output;

public interface NotifyPort {
    void notifyError(Exception e);
    void notifyInfo(String message); // 스케쥴러 시작/종료 등 일반 정보성 알림
}
