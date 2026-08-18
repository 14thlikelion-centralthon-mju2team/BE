package com.hq.backend.place;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Component;

// TRD §14.3 — 장소 좌표는 애플리케이션 레벨 AES-GCM 암호화. 형식(double -> 문자열 -> UTF-8
// 바이트 -> 암호화)이 쓰기·읽기 양쪽에서 정확히 일치해야 해서 공용 컴포넌트로 분리했다
// (PlaceService·BootstrapService 둘 다 쓴다). calendarTokenEncryptor는 캘린더 전용이 아니라
// app.encryption.* 설정을 쓰는 앱 공용 암호화기다(CalendarConnection.refreshTokenEnc와 동일 빈).
@Component
@RequiredArgsConstructor
public class PlaceCoordinateCodec {

    private final BytesEncryptor calendarTokenEncryptor;

    public byte[] encode(double value) {
        return calendarTokenEncryptor.encrypt(Double.toString(value).getBytes(StandardCharsets.UTF_8));
    }

    public double decode(byte[] encrypted) {
        return Double.parseDouble(new String(calendarTokenEncryptor.decrypt(encrypted), StandardCharsets.UTF_8));
    }
}
