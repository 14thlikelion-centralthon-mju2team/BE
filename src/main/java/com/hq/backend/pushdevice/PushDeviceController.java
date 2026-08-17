package com.hq.backend.pushdevice;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.pushdevice.dto.PushDeviceResponse;
import com.hq.backend.pushdevice.dto.RegisterPushDeviceRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/push-devices")
@RequiredArgsConstructor
public class PushDeviceController {

    private final PushDeviceService pushDeviceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PushDeviceResponse register(@CurrentUserId UUID userId, @Valid @RequestBody RegisterPushDeviceRequest request) {
        return pushDeviceService.register(userId, request);
    }
}
