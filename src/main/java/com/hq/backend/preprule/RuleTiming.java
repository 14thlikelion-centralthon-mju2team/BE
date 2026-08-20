package com.hq.backend.preprule;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleTiming {
    PRE_DEPARTURE, POST_ARRIVAL;

    // wire 값은 API 명세·DB CHECK 제약과 같은 lower_snake다. name() 그대로 나가면
    // 대문자가 되어 클라이언트 파싱이 깨진다. 역직렬화는 Jackson이 이 값을 그대로 쓰고,
    // accept-case-insensitive-enums 설정이 대문자 요청도 계속 받아준다.
    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
