package com.hq.backend.pushdevice;

import com.hq.backend.pushdevice.dto.PushDeviceResponse;
import com.hq.backend.pushdevice.dto.RegisterPushDeviceRequest;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PushDeviceService {

    private final PushDeviceRepository pushDeviceRepository;

    // installation_id가 UNIQUE라 같은 기기의 재등록(토큰 갱신, 다른 계정으로 로그인 전환
    // 포함)은 새 행이 아니라 기존 행을 갱신한다.
    @Transactional
    public PushDeviceResponse register(UUID userId, RegisterPushDeviceRequest request) {
        Instant now = Instant.now();
        PushDevice device = pushDeviceRepository.findByInstallationId(request.installationId())
                .orElseGet(() -> PushDevice.builder()
                        .installationId(request.installationId())
                        .build());

        device.setUserId(userId);
        device.setCurrentToken(request.token());
        device.setPlatform(request.platform().name().toLowerCase());
        device.setTokenStatus("active");
        device.setLastSeenAt(now);

        PushDevice saved = pushDeviceRepository.save(device);

        return new PushDeviceResponse(
                saved.getPushDeviceId(),
                saved.getInstallationId(),
                saved.getPlatform(),
                saved.getLastSeenAt());
    }
}
