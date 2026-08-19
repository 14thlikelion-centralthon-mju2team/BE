"""Approved wellness copy (TR-09 · PRD §14.8 · TRD §17.5).

사용자에게 나가는 모든 문구가 이 테이블에만 존재합니다. 엔진은 슬롯만 치환하고 문장을
조립하지 않으므로, 의료 경계 카피 린트가 검사할 대상이 유한 목록으로 고정됩니다.
자유 생성 LLM은 이 경계를 확률적으로만 지키기 때문에 사용하지 않습니다.

금지 표현 (PRD §14.8): 진단 · 치료 · 복용량 · 피부 상태 판정 · 제품 효능 보장.
아래 문구는 전부 "확인하세요" 수준의 행동 안내이며, 건강 효과를 확정하지 않습니다.
선크림 재도포 주기도 사용자가 정한 값이고 서비스가 SPF·피부 타입을 판단하지 않습니다
(PRD §14.7).
"""

from app.domain.wellness_engine.enums import CardScenario, WellnessActionCode

#: 준비 카드와 푸시에 표시되는 행동 이름.
ACTION_LABELS: dict[WellnessActionCode, str] = {
    WellnessActionCode.UV_PROTECT: "선크림·모자·양산 확인",
    WellnessActionCode.PM_MASK: "마스크 확인",
    WellnessActionCode.TEMP_HEAT_PREP: "물·가벼운 복장 확인",
    WellnessActionCode.TEMP_COLD_PREP: "겉옷·보온 준비",
    WellnessActionCode.RAIN_GEAR: "우산·방수 준비",
    WellnessActionCode.UV_REAPPLY: "선크림 재도포 확인",
    WellnessActionCode.PM_RECHECK: "마스크 상태 한 번 확인",
    WellnessActionCode.HYDRATION_INTAKE: "수분 보충 확인",
}

#: 근거 문장. 슬롯은 숫자와 양자화 구간 이름만 받는다.
ACTION_REASONS: dict[WellnessActionCode, str] = {
    WellnessActionCode.UV_PROTECT: "자외선지수 {uv_index} · 야외 {outdoor_minutes}분",
    WellnessActionCode.PM_MASK: "대기질 {pm_grade} · 야외 {outdoor_minutes}분",
    WellnessActionCode.TEMP_HEAT_PREP: "체감온도 {feels_like}℃ · 야외 {outdoor_minutes}분",
    WellnessActionCode.TEMP_COLD_PREP: "체감온도 {feels_like}℃ · 야외 {outdoor_minutes}분",
    WellnessActionCode.RAIN_GEAR: "강수 확률 {precipitation}% · 야외 {outdoor_minutes}분",
    WellnessActionCode.UV_REAPPLY: "자외선지수 {uv_index} · 야외 노출 계속",
    WellnessActionCode.PM_RECHECK: "대기질 {pm_grade} · 야외 노출 계속",
    WellnessActionCode.HYDRATION_INTAKE: "체감온도 {feels_like}℃ · 야외 노출 계속",
}

#: 근거 문장의 대기질 슬롯 값 (에어코리아 등급 표기).
PM_GRADE_LABELS: dict[str, str] = {
    "good": "좋음",
    "moderate": "보통",
    "bad": "나쁨",
    "very_bad": "매우나쁨",
}

#: 사용자가 등록한 준비 항목과 웰니스 제안이 같은 것을 가리키는지 판단하는
#: 승인 키워드 표.  의미 추론이 아니라 고정 목록이며, 이름은 앞뒤 공백 제거와
#: 대소문자 통일까지만 정규화한다 (§5.4, 골든 09).
MERGE_KEYWORDS: dict[WellnessActionCode, tuple[str, ...]] = {
    WellnessActionCode.UV_PROTECT: ("선크림", "자외선차단", "썬크림", "양산", "모자"),
    WellnessActionCode.PM_MASK: ("마스크",),
    WellnessActionCode.TEMP_HEAT_PREP: ("물", "텀블러", "생수"),
    WellnessActionCode.TEMP_COLD_PREP: ("겉옷", "외투", "목도리", "장갑"),
    WellnessActionCode.RAIN_GEAR: ("우산", "우의", "레인부츠", "방수"),
    WellnessActionCode.UV_REAPPLY: ("선크림", "자외선차단", "썬크림"),
    WellnessActionCode.PM_RECHECK: ("마스크",),
    WellnessActionCode.HYDRATION_INTAKE: ("물", "텀블러", "생수"),
}

#: 병합된 준비 항목에 붙는 근거 접두사 (§5.4 — 사용자 항목의 source_type은 유지).
MERGED_REASON_PREFIX = "이미 등록한 준비 항목 · "

#: 일일 마무리 카드 (§7.5).  ``card_scenario``는 ERD 값 5종을 그대로 쓴다.
CARD_MESSAGES: dict[CardScenario, str] = {
    CardScenario.RUSHED: (
        "오늘은 일정 {event_count}건을 촉박하게 움직였습니다. "
        "다음 일정은 준비 시간을 조금 더 두는 편이 편합니다."
    ),
    CardScenario.DENSITY: (
        "오늘은 일정 {event_count}건이 이어졌습니다. "
        "사이사이 쉬는 시간을 넣어두면 다음이 수월합니다."
    ),
    CardScenario.EXPOSURE: (
        "오늘은 야외 이동이 {outdoor_minutes}분이었습니다. 실내에서 잠시 쉬어가세요."
    ),
    CardScenario.STABLE: "오늘은 일정 {event_count}건을 계획대로 마쳤습니다.",
    CardScenario.DEFAULT: "오늘 일정을 마쳤습니다.",
}

#: 야외 시간을 추정조차 할 수 없을 때 쓰는 변형 — 숫자를 지어내지 않는다 (§7.5).
CARD_MESSAGES_WITHOUT_NUMBERS: dict[CardScenario, str] = {
    CardScenario.EXPOSURE: "오늘은 야외 이동이 길었습니다. 실내에서 잠시 쉬어가세요.",
    CardScenario.RUSHED: (
        "오늘은 일정이 촉박했습니다. 다음 일정은 준비 시간을 조금 더 두는 편이 편합니다."
    ),
    CardScenario.DENSITY: "오늘은 일정이 이어졌습니다. 사이사이 쉬는 시간을 넣어두세요.",
    CardScenario.STABLE: "오늘은 일정을 계획대로 마쳤습니다.",
    CardScenario.DEFAULT: "오늘 일정을 마쳤습니다.",
}
