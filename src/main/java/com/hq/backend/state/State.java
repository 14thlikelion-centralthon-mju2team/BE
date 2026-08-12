package com.hq.backend.state;

// V1__init.sql v_daily_states.signal(소문자 green/yellow/red) + 근거 부족 시 DATA_INSUFFICIENT (D-014).
// Green으로 추정 금지 — 뷰에 행이 없으면 반드시 이 값을 반환한다.
public enum State {
    GREEN,
    YELLOW,
    RED,
    DATA_INSUFFICIENT
}
