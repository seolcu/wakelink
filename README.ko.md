# WakeLink

[English](README.md) | **한국어**

WakeLink는 믿는 사람의 폰에서 원할 때 큰 알람을 울려 주는 앱이에요. 상대
폰이 무음 모드여도 울려요. 그래서 친구가 알람 맞추는 걸 깜빡했어도 대신
깨워 줄 수 있어요.

- **`server/`** -- Go 백엔드예요. 서로 동의해야 하는 연결(페어링), 방향별
  깨우기 권한, FCM으로 보내는 깨우기 푸시를 맡아요.
  [`server/README.md`](server/README.md)를 참고하세요.
- **`android/`** -- 하나뿐인 Android 앱이에요. 설치하면 누구든 다른 사람을
  깨울 수도, 깨워질 수도 있어요. "보내는 앱"과 "받는 앱"이 따로 있지 않아요.

## 설치

웹사이트: **<https://wake.seolcu.com>**. 최신 APK 받기:
**<https://wake.seolcu.com/download>**
([GitHub 최신 릴리스](https://github.com/seolcu/wakelink/releases/latest)로
연결돼요). WakeLink는 플레이 스토어에 없어서, 처음 한 번은 브라우저나 파일
앱에 "출처를 알 수 없는 앱 설치"를 허용해야 해요.

앱은 한국어와 영어를 지원하고, 폰 언어를 따라가요.

설치한 뒤로는 앱이 알아서 최신 상태를 유지해요. 앱을 열 때마다 새 릴리스가
있는지 확인하고(설정 → 앱 업데이트에서 직접 확인할 수도 있어요), 받아서
시스템 설치 화면을 띄워요. 확인만 누르면 돼요.

## 동작 원리, 한 문단 요약

Android의 "무음" 모드는 벨소리와 알림 소리 채널만 끄고 `STREAM_ALARM`은 끄지
않아요. 그래서 일반 알람 앱은 무음 모드에서도 항상 울려요. WakeLink도 받는
쪽에서 같은 채널로 알람을 울리기 때문에 특별한 권한이 전혀 필요 없어요.
`STREAM_ALARM`까지 막는 건 방해 금지 모드의 "완전 무음"뿐이에요. 이 경우만
설정에서 직접 켜는 옵션(알림 정책 접근 권한)으로 처리하고, 기본값은
꺼져 있어요.

## 동의를 전제로 한 설계

연결하려면 양쪽 모두 직접 행동해야 해요. 한쪽이 초대 코드를 만들고, 다른
쪽이 그 코드를 직접 입력해야 해요. 누군가를 일방적으로 추가하는 방법은
없어요. 양쪽 모두 언제든 깨우기 권한을 거두거나 연결을 아예 끊을 수 있어요.
앱이 켜져서 깨워질 수 있는 상태라면 항상 알림으로 보여요. 숨겨진 채로
동작하지 않아요.

## 개발 시작하기

- 백엔드: [`server/README.md`](server/README.md)
- 나만의 독립 인스턴스 직접 호스팅하기(필요한 Firebase 프로젝트 포함):
  [`docs/self-hosting.md`](docs/self-hosting.md)
- Android 프로젝트: Android Studio에서 `android/`를 열어요. FCM이 실제로
  동작하려면 자리만 채워 둔 `android/app/google-services.json`을 내 Firebase
  프로젝트의 파일로 바꿔야 해요.

개발 문서는 영어로만 작성되어 있어요.

## 라이선스

MIT -- [`LICENSE`](LICENSE)를 참고하세요.

Android 앱에는 [Pretendard](https://github.com/orioncactus/pretendard) 글꼴(SIL
Open Font License 1.1)이 포함되어 있어요.
[`android/third_party/pretendard/LICENSE.txt`](android/third_party/pretendard/LICENSE.txt)를
참고하세요.
