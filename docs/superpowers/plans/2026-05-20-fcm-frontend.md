# FCM 알림 — 프론트엔드(kista-ui) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kista-ui(Next.js)에 Firebase Web Push를 통합한다. Service Worker 등록, FCM 토큰 발급, 토큰을 백엔드에 등록하는 훅, 설정 화면에서 알림 채널 선택 UI를 구현한다.

**Prerequisites:** kista-api FCM 백엔드 플랜(`2026-05-20-fcm-backend.md`) 구현 완료 및 `FIREBASE_SERVICE_ACCOUNT_JSON` 환경변수 설정 필요.

**Architecture:** Firebase JS SDK의 `getToken(messaging, { vapidKey })` 으로 토큰 발급 → `POST /api/fcm/tokens` 등록. 알림 채널 선택은 `PATCH /api/settings/notification-channel`로 저장. Service Worker(`firebase-messaging-sw.js`)가 백그라운드 메시지 수신.

**Tech Stack:** Next.js 16 App Router, TypeScript, Firebase JS SDK 11.x, Tailwind CSS, Shadcn UI

---

### Task 1: Firebase JS SDK + 환경변수 설정

**Files:**
- Modify: `package.json`
- Create: `.env.local` (gitignored)
- Modify: `next.config.ts`

- [ ] **Step 1: firebase 패키지 설치**

```bash
cd /Users/phs/workspace/kista/kista-ui
npm install firebase
```
Expected: `firebase` 패키지가 `node_modules`에 추가됨

- [ ] **Step 2: .env.local에 Firebase 설정값 추가**

> **사전 작업 (수동):** Firebase 콘솔 → 프로젝트 설정 → 앱 → Firebase SDK 설정 값 복사.  
> VAPID 키: Firebase 콘솔 → 프로젝트 설정 → Cloud Messaging → 웹 푸시 인증서 → 키 쌍 생성

`.env.local` (gitignored, 직접 생성):
```bash
NEXT_PUBLIC_FIREBASE_API_KEY=your_api_key
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=your_project.firebaseapp.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=your_project_id
NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=your_project.appspot.com
NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=your_sender_id
NEXT_PUBLIC_FIREBASE_APP_ID=your_app_id
NEXT_PUBLIC_FIREBASE_VAPID_KEY=your_vapid_key
```

- [ ] **Step 3: 커밋**

```bash
cd /Users/phs/workspace/kista/kista-ui
git add package.json package-lock.json
git commit -m "build: firebase JS SDK 11.x 의존성 추가"
```

---

### Task 2: Firebase 앱 초기화 + Messaging 인스턴스

**Files:**
- Create: `lib/firebase.ts`

- [ ] **Step 1: firebase.ts 작성**

```typescript
// lib/firebase.ts
import { initializeApp, getApps, getApp, FirebaseApp } from 'firebase/app';
import { getMessaging, Messaging } from 'firebase/messaging';

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

function getFirebaseApp(): FirebaseApp {
  return getApps().length === 0 ? initializeApp(firebaseConfig) : getApp();
}

// 브라우저 환경에서만 Messaging 인스턴스 반환 (SSR 환경 안전)
export function getFirebaseMessaging(): Messaging | null {
  if (typeof window === 'undefined') return null;
  try {
    return getMessaging(getFirebaseApp());
  } catch {
    return null;
  }
}
```

- [ ] **Step 2: TypeScript 컴파일 확인**

```bash
npx tsc --noEmit
```
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add lib/firebase.ts
git commit -m "feat: Firebase 앱 초기화 모듈 추가"
```

---

### Task 3: Service Worker 등록 (백그라운드 알림)

**Files:**
- Create: `public/firebase-messaging-sw.js`

- [ ] **Step 1: firebase-messaging-sw.js 작성**

```javascript
// public/firebase-messaging-sw.js
// Firebase SDK 버전은 package.json의 firebase 버전과 맞출 것
importScripts('https://www.gstatic.com/firebasejs/11.0.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/11.0.0/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: self.FIREBASE_API_KEY,
  authDomain: self.FIREBASE_AUTH_DOMAIN,
  projectId: self.FIREBASE_PROJECT_ID,
  storageBucket: self.FIREBASE_STORAGE_BUCKET,
  messagingSenderId: self.FIREBASE_MESSAGING_SENDER_ID,
  appId: self.FIREBASE_APP_ID,
});

const messaging = firebase.messaging();

// 백그라운드 메시지 수신 시 알림 표시
messaging.onBackgroundMessage((payload) => {
  const { title, body } = payload.notification ?? {};
  self.registration.showNotification(title ?? 'KISTA', {
    body: body ?? '',
    icon: '/icon-192.png',
  });
});
```

> **주의:** Service Worker에서 `process.env`가 동작하지 않으므로, 빌드 시 환경변수를 주입하거나 `next.config.ts`에서 `public/` 파일을 처리하는 방식을 사용. 가장 간단한 방법은 Service Worker를 `next.config.ts`의 `rewrites`나 별도 route handler를 통해 동적으로 제공하는 것이다.
>
> **대안 (권장):** `public/firebase-messaging-sw.js`에 하드코딩하지 말고, `/firebase-messaging-sw.js` 경로의 App Router route handler로 환경변수를 주입하는 방식 사용:

```typescript
// app/firebase-messaging-sw.js/route.ts
export async function GET() {
  const config = {
    apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
    authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
    projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
    storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
    messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
    appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
  };
  const body = `
importScripts('https://www.gstatic.com/firebasejs/11.0.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/11.0.0/firebase-messaging-compat.js');
firebase.initializeApp(${JSON.stringify(config)});
const messaging = firebase.messaging();
messaging.onBackgroundMessage((payload) => {
  const { title, body } = payload.notification ?? {};
  self.registration.showNotification(title ?? 'KISTA', { body: body ?? '', icon: '/icon-192.png' });
});
`;
  return new Response(body, {
    headers: { 'Content-Type': 'application/javascript' },
  });
}
```

- [ ] **Step 2: 커밋**

```bash
git add app/firebase-messaging-sw.js/
git commit -m "feat: Firebase Service Worker route handler — 백그라운드 알림 수신"
```

---

### Task 4: FCM 토큰 발급 + 서버 등록 훅

**Files:**
- Create: `lib/fcm.ts`
- Create: `hooks/useFcmToken.ts`

- [ ] **Step 1: fcm.ts 유틸 함수 작성**

```typescript
// lib/fcm.ts
import { getToken } from 'firebase/messaging';
import { getFirebaseMessaging } from './firebase';

const VAPID_KEY = process.env.NEXT_PUBLIC_FIREBASE_VAPID_KEY ?? '';

/** 브라우저 알림 권한 요청 후 FCM 토큰 반환. 권한 거부 시 null 반환 */
export async function requestFcmToken(): Promise<string | null> {
  const messaging = getFirebaseMessaging();
  if (!messaging) return null;

  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return null;

  try {
    return await getToken(messaging, {
      vapidKey: VAPID_KEY,
      serviceWorkerRegistration: await navigator.serviceWorker.register('/firebase-messaging-sw.js'),
    });
  } catch (error) {
    console.error('FCM 토큰 발급 실패:', error);
    return null;
  }
}

/** 백엔드에 FCM 토큰 등록 */
export async function registerTokenToServer(token: string, accessToken: string): Promise<void> {
  await fetch('/api/fcm/tokens', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({ token, platform: 'WEB' }),
  });
}

/** 백엔드에서 FCM 토큰 삭제 */
export async function unregisterTokenFromServer(token: string, accessToken: string): Promise<void> {
  await fetch(`/api/fcm/tokens/${encodeURIComponent(token)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}
```

- [ ] **Step 2: useFcmToken 훅 작성**

```typescript
// hooks/useFcmToken.ts
'use client';

import { useState, useCallback } from 'react';
import { requestFcmToken, registerTokenToServer } from '@/lib/fcm';

type Status = 'idle' | 'requesting' | 'registered' | 'denied' | 'error';

/**
 * FCM 알림 권한 요청 + 토큰 서버 등록 훅.
 * accessToken: 현재 로그인된 JWT
 */
export function useFcmToken(accessToken: string | null) {
  const [status, setStatus] = useState<Status>('idle');

  const requestAndRegister = useCallback(async () => {
    if (!accessToken) return;
    setStatus('requesting');
    try {
      const token = await requestFcmToken();
      if (!token) {
        setStatus('denied');
        return;
      }
      await registerTokenToServer(token, accessToken);
      setStatus('registered');
    } catch {
      setStatus('error');
    }
  }, [accessToken]);

  return { status, requestAndRegister };
}
```

- [ ] **Step 3: TypeScript 컴파일 확인**

```bash
npx tsc --noEmit
```
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add lib/fcm.ts hooks/useFcmToken.ts
git commit -m "feat: FCM 토큰 발급/등록 유틸 + useFcmToken 훅"
```

---

### Task 5: 알림 채널 선택 UI (설정 화면 통합)

**Files:**
- 설정 화면 컴포넌트 파일 (프로젝트 내 기존 설정 페이지 경로 확인 필요)

- [ ] **Step 1: 기존 설정 화면 파일 확인**

```bash
find /Users/phs/workspace/kista/kista-ui/app -name "*.tsx" | grep -i "setting\|profile\|config" | head -10
```

- [ ] **Step 2: 알림 채널 선택 컴포넌트 추가**

설정 화면(또는 별도 `components/NotificationSettings.tsx`)에 아래 UI 추가:

```tsx
'use client';

import { useState } from 'react';
import { useFcmToken } from '@/hooks/useFcmToken';

type Channel = 'TELEGRAM' | 'FCM' | 'ALL';

interface NotificationSettingsProps {
  currentChannel: Channel;
  accessToken: string | null;
  onChannelChange: (channel: Channel) => Promise<void>;
}

export function NotificationSettings({
  currentChannel,
  accessToken,
  onChannelChange,
}: NotificationSettingsProps) {
  const [channel, setChannel] = useState<Channel>(currentChannel);
  const { status, requestAndRegister } = useFcmToken(accessToken);

  async function handleChannelSelect(next: Channel) {
    // FCM 선택 시 먼저 토큰 등록
    if ((next === 'FCM' || next === 'ALL') && status !== 'registered') {
      await requestAndRegister();
    }
    await onChannelChange(next);
    setChannel(next);
  }

  const channels: { value: Channel; label: string; desc: string }[] = [
    { value: 'TELEGRAM', label: '텔레그램', desc: '텔레그램 봇 알림' },
    { value: 'FCM', label: '푸시 알림', desc: '브라우저 / 모바일 푸시' },
    { value: 'ALL', label: '모두', desc: '텔레그램 + 푸시 동시 수신' },
  ];

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">알림 수단</p>
      <div className="flex gap-2">
        {channels.map((c) => (
          <button
            key={c.value}
            onClick={() => handleChannelSelect(c.value)}
            className={`rounded-lg border px-3 py-2 text-sm transition-colors ${
              channel === c.value
                ? 'border-rose-400 bg-rose-50 text-rose-700'
                : 'border-gray-200 hover:border-gray-300'
            }`}
          >
            <div className="font-medium">{c.label}</div>
            <div className="text-xs text-gray-500">{c.desc}</div>
          </button>
        ))}
      </div>
      {status === 'denied' && (
        <p className="text-xs text-red-500">브라우저 알림 권한이 거부되었습니다. 브라우저 설정에서 허용해주세요.</p>
      )}
      {status === 'registered' && (
        <p className="text-xs text-green-600">푸시 알림이 등록되었습니다.</p>
      )}
    </div>
  );
}
```

- [ ] **Step 3: 기존 설정 페이지에서 NotificationSettings 컴포넌트 연결**

설정 화면에서 `PATCH /api/settings/notification-channel` 호출 함수 작성 후 `NotificationSettings`에 전달:

```typescript
async function changeChannel(channel: 'TELEGRAM' | 'FCM' | 'ALL') {
  await fetch('/api/settings/notification-channel', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({ channel }),
  });
}
```

- [ ] **Step 4: TypeScript 컴파일 확인**

```bash
npx tsc --noEmit
```
Expected: 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add components/ app/
git commit -m "feat: 알림 채널 선택 UI (NotificationSettings) + 설정 화면 통합"
```

---

## 파일 변경 요약

| 파일 | 작업 |
|------|------|
| `package.json` | `firebase` SDK 추가 |
| `.env.local` | Firebase 환경변수 (gitignored, 수동 설정) |
| `lib/firebase.ts` | Firebase 앱 초기화 |
| `lib/fcm.ts` | 토큰 발급/등록/삭제 유틸 |
| `hooks/useFcmToken.ts` | 권한 요청 + 등록 훅 |
| `app/firebase-messaging-sw.js/route.ts` | Service Worker (환경변수 주입) |
| `components/NotificationSettings.tsx` | 알림 채널 선택 UI |
| 기존 설정 페이지 | NotificationSettings 통합 |
