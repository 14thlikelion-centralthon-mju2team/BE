package com.hq.backend.environment.dto;

import com.hq.backend.provider.EnvironmentSnapshot;

// 홈 날씨 카드 전용. 클라이언트가 이 값을 그대로 화면에 출력하므로 한국어 표시
// 문자열로 내린다(엔진이 쓰는 영문 어휘는 plan_context 쪽에 그대로 남는다).
// 값이 없으면 null이고, 클라이언트는 null인 항목만 생략한다.
public record EnvironmentResponse(
        Integer temperature,
        String sky,
        String pm10Grade,
        String pm25Grade,
        Integer uvIndex
) {

    public static EnvironmentResponse from(EnvironmentSnapshot snapshot) {
        return new EnvironmentResponse(
                (int) Math.round(snapshot.tempC()),
                skyLabel(snapshot.sky()),
                gradeLabel(snapshot.pm10Grade()),
                gradeLabel(snapshot.pm25Grade()),
                // 제공자 실패 시 -1로 채워지는 값이라 음수는 "모름"으로 본다.
                snapshot.uvIndex() < 0 ? null : (int) Math.round(snapshot.uvIndex()));
    }

    private static String skyLabel(String sky) {
        return switch (sky == null ? "" : sky) {
            case "clear" -> "맑음";
            case "partly_cloudy" -> "구름많음";
            case "cloudy" -> "흐림";
            default -> null;
        };
    }

    private static String gradeLabel(String grade) {
        return switch (grade == null ? "" : grade) {
            case "good" -> "좋음";
            case "moderate" -> "보통";
            case "bad" -> "나쁨";
            case "very_bad" -> "매우나쁨";
            default -> null;
        };
    }
}
