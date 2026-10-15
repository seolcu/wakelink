<p align="center">
  <a href="https://wake.seolcu.com"><img src="server/internal/web/assets/og.png" alt="WakeLink — 무음이어도 울리는, 친구가 보내는 알람" width="720"></a>
</p>

<p align="center">
  <a href="https://github.com/seolcu/wakelink/releases/latest"><img src="https://img.shields.io/github/v/release/seolcu/wakelink?label=release&color=E8892E" alt="최신 릴리스"></a>
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-1F2B4D" alt="Android 8.0 이상">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/seolcu/wakelink?color=1F2B4D" alt="MIT 라이선스"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · <b>한국어</b>
</p>

<p align="center">
  <a href="https://wake.seolcu.com/download"><b>Android 앱 받기</b></a>
  &nbsp;·&nbsp;
  <a href="https://wake.seolcu.com"><b>웹사이트</b></a>
</p>

---

**WakeLink는 믿는 사람의 폰에 원할 때 큰 알람을 울려 주는 앱이에요.**
상대 폰이 무음 모드여도 울리니, 알람 맞추는 걸 깜빡한 날에도 친구가 대신 깨워 줄 수 있어요.

- **무음·진동 모드에서도 울림.** 특별한 권한은 필요 없어요.
- **실제로 울렸는지 확인.** "보냄"과 "울림"을 구분해서 보여 줘요.
- **서로 동의한 사람끼리만 연결.** 끄는 것도 각자 언제든 가능.
- **내 소리, 내 사진으로 깨우기.** 기본 벨소리, 목소리 녹음, 오디오 파일 중에 고르고, 사진이나 GIF(그 자리에서 찍은 사진도)를 상대 알람 화면에 띄울 수 있어요.
- **종단간 암호화.** 이름, 녹음, 파일, 사진은 받는 사람 폰만 열 수 있고, 서버는 암호문만 전달해요.
- **받는 사람이 결정.** 볼륨(점점 커지게 포함), 다른 사람이 고른 소리의 허용 범위, 사진 표시 여부.
- **재부팅 후 잠금 해제 전에도 울림.** 새벽에 자동 재시작돼도 아침 알람은 와요.
- **앱은 하나.** 설치하면 누구든 깨울 수도, 깨워질 수도 있어요.
- **한국어와 영어 지원.** 폰 언어를 따라가요.

## 설치

1. **<https://wake.seolcu.com/download>** 에서 APK를 받아요. 항상 [최신 릴리스](https://github.com/seolcu/wakelink/releases/latest)로 연결돼요.
2. 받은 파일을 열어요. 플레이 스토어 앱이 아니라서, 처음 한 번은 "출처를 알 수 없는 앱 설치"를 허용해야 해요.
3. 이름을 정하고, 깨워 줄 사람과 초대 코드를 주고받아요.
4. 깨우기에 필요한 권한(알림, 전체 화면 알람, 배터리 최적화)은 앱이 차례로 안내해요.

이후 업데이트는 앱이 알아서 챙겨요. 새 버전이 나오면 알림이 오고, 누르면 바로 설치 화면이 열려요. **설정 → 앱 업데이트**에서 직접 확인도 가능.

## 무음 모드에서도 울리는 이유

Android의 무음 모드는 벨소리와 알림 소리만 끄고 **알람 소리(`STREAM_ALARM`)는 그대로 둬요.**
그래서 시계 앱 알람이 무음에서도 울리고, 같은 알람 채널을 쓰는 WakeLink도 울려요.

| 폰 상태 | WakeLink |
| --- | --- |
| 소리 / 진동 / 무음 | 울림 |
| 방해 금지 (알람 허용) | 울림 |
| 방해 금지 "완전 무음" | 설정에서 켜면 울림 (알림 정책 접근 권한). 울리는 동안만 바꾸고 끝나면 원래대로 |

## 동의를 전제로 한 설계

- **양쪽 동의가 있어야 연결.** 한 사람이 만든 초대 코드를 다른 사람이 직접 입력해야 하고, 몰래 추가하는 방법은 없어요.
- **방향별로 따로 허락.** "나는 깨울 수 있지만 상대는 나를 못 깨우게"처럼 각자 자기 쪽만 정해요.
- **언제든 거두기.** 앱 전체 끄기, 특정 사람만 막기, 연결 끊기 모두 가능.
- **상태는 늘 첫 화면에.** 지금 깨우기를 받는 중인지 맨 위에 표시되고, 거기서 바로 끌 수 있어요.
- **장난 방지.** 같은 사람에게는 30초에 한 번, 24시간에 20번까지만.
- **개인정보 보호.** 양쪽 앱이 지원하면 깨우기 내용과 올린 파일이 종단간 암호화돼요. 올린 파일은 올린 사람이 깨울 수 있는 상대만 받을 수 있고, 계정을 지우면 함께 삭제.

## 깨우기가 전달되는 과정

```mermaid
sequenceDiagram
    participant A as 친구 폰
    participant S as wakelinkd (서버)
    participant F as Firebase Cloud Messaging
    participant B as 내 폰
    A->>S: POST /v1/pairings/{id}/trigger
    S->>S: 연결, 권한, 횟수 제한 확인
    S->>F: 데이터 전용, 높은 우선순위 푸시
    F->>B: 깨우기 메시지
    B->>B: STREAM_ALARM으로 전체 화면 알람
    B->>S: POST /v1/triggers/{id}/ack (소리가 나기 시작하면)
    A->>S: 상태 확인
    S-->>A: 상태: 울림 확인됨
```

## 개발

| 폴더 | 내용 |
| --- | --- |
| [`server/`](server/README.md) | Go 백엔드(`wakelinkd`): 연결, 방향별 권한, FCM 푸시, SQLite, 그리고 [웹사이트](https://wake.seolcu.com) |
| [`android/`](android/) | Kotlin / Jetpack Compose 앱 |
| [`docs/`](docs/) | [연결 과정](docs/pairing-flow.md)과 [직접 호스팅](docs/self-hosting.md) 안내 |

<details>
<summary><b>빌드와 직접 호스팅</b></summary>

- **백엔드**: [`server/README.md`](server/README.md) 참고.
- **Android**: Android Studio에서 `android/`를 열고, `android/app/google-services.json.example`을 `google-services.json`으로 복사해요. 푸시 알림이 실제로 동작하려면 내 Firebase 프로젝트의 파일로 바꿔야 해요.
- **나만의 서버 운영**: 푸시 알림이 Firebase 프로젝트 단위로 묶여 있어서, 서버 주소만 바꿔서는 동작하지 않아요. 내 Firebase 프로젝트로 앱도 직접 빌드해야 하고, 과정은 [`docs/self-hosting.md`](docs/self-hosting.md)에 있어요.

개발 문서는 영어로만 있어요.

</details>

## 라이선스

[MIT](LICENSE). Android 앱에 포함된 [Pretendard](https://github.com/orioncactus/pretendard) 글꼴은 SIL Open Font License 1.1을 따라요([`android/third_party/pretendard/LICENSE.txt`](android/third_party/pretendard/LICENSE.txt)).
