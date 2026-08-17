package com.hq.backend.bootstrap;

import com.hq.backend.bootstrap.dto.BootstrapResponse;
import com.hq.backend.common.auth.CurrentUserId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/bootstrap")
@RequiredArgsConstructor
public class BootstrapController {

    private final BootstrapService bootstrapService;

    @GetMapping
    public BootstrapResponse bootstrap(@CurrentUserId UUID userId) {
        return bootstrapService.bootstrap(userId);
    }
}
