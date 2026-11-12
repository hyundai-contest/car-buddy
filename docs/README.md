# Car Buddy 문서 안내

2026-11-12 기준. 처음이라면 위에서부터 읽으면 됩니다.

## 지금 기준이 되는 문서

| 문서 | 내용 | 언제 보나 |
| --- | --- | --- |
| [PLAN_v14.md](PLAN_v14.md) | 기획안 최신본(제출본). 차량 특화 요소, 에피소드 15종, 시나리오, SDK 사용 현황, 일정 | 무엇을 왜 만드는지 |
| [design/card_mockup.html](design/card_mockup.html) | 카드·화면 시안 13장. 브라우저로 열면 됨 | 화면이 어떻게 생겼는지 |
| [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) | 구현하면서 정한 세부 결정, 스펙과 다르게 간 부분, 아직 안 한 것 | 코드가 왜 이렇게 됐는지 |
| [CHECKLIST.md](CHECKLIST.md) | 진행 기록과 남은 일(1차 11/15, PT 12/3) | 어디까지 됐는지 |
| [NEXT_DESIGN.md](NEXT_DESIGN.md) | 개선 설계. N1~N3 구현됨, N4~N7이 PT까지 할 일 | 다음에 무엇을 만드는지 |
| [VIDEO.md](VIDEO.md) | 1분 30초 시연 영상 촬영 순서 | 영상 찍을 때 |

## 설계 문서 (11/12 개정, v2)

서버·동승자 폰 참여·음성 클립은 로드맵으로 내리고, 여정 끝 판정·알림·중간 상영·배웅·장면별 앰비언트를 반영했습니다. 내용이 서로 다르면 위 표의 문서가 우선입니다.

| 문서 | 내용 |
| --- | --- |
| [SPEC.md](SPEC.md) | 개발 설계서 v2: 신호 계층, 여정 상태 머신과 시동 OFF 복구, 감지기 명세, 영수증 계산, 카드 데이터 |
| [UX_SPEC.md](UX_SPEC.md) | 화면·흐름·문구 명세 v2: 화면별 가로·세로 배치, 알림, 문구 키 |
| [E2E.md](E2E.md) | 실사용 흐름 v2: 출발, 목적지 없는 날, 도착, 다음 탑승 |

## 참고 자료

| 자료 | 내용 |
| --- | --- |
| [signals_phase0.md](signals_phase0.md) | Pleos 에뮬레이터에서 실제로 확인한 SDK 호출과 Virtual Console HTTP 조작법 |
| [replay/scenario_a.jsonl](replay/scenario_a.jsonl) | 주행 재생 로그: 토요일 서울 → 강릉(에피소드 6종, 휴게소 시동 OFF, 지각 도착) |
| [replay/commute_b.jsonl](replay/commute_b.jsonl) | 주행 재생 로그: 평일 퇴근, 목적지 없음 |

재생 로그는 테스트 픽스처이자 앱 assets라 파일 이름과 위치를 바꾸면 안 됩니다. 생성 스크립트는 저장소의 `tools/`에 있습니다.

## 원본이 다른 곳에 있는 문서

- 기획안: [Claude Doc](https://claude.ai/artifact/LtUyhvQQgsBVwHA91DaE7H#7d11581f-400b)의 '기획안 v14 (제출본)' 탭. `PLAN_v14.md`는 그 탭을 내보낸 사본입니다. 이전 버전(v8~v13)은 같은 문서의 다른 탭에 남아 있습니다.
- 시안: [디자인 캔버스](https://claude.ai/artifact/Buj2Z1T5WTQ4Ngim6rQWPx). `design/card_mockup.html`은 캔버스의 아트보드를 한 페이지로 모은 사본입니다.
- 체크리스트: [Claude Doc](https://claude.ai/artifact/YX6WaKsncj2wg7Zs9Uk6u3). `CHECKLIST.md`는 사본입니다.
