package com.hq.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hq.backend.auth.JwtService;
import com.hq.backend.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.NativeWebRequest;

@ExtendWith(MockitoExtension.class)
class CurrentUserArgumentResolverTest {

    @Mock private JwtService jwtService;
    @Mock private NativeWebRequest webRequest;
    @Mock private HttpServletRequest servletRequest;

    @Test
    void authorization_헤더가_없으면_401() {
        var resolver = new CurrentUserArgumentResolver(jwtService);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(servletRequest);
        when(servletRequest.getHeader("Authorization")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, webRequest, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "UNAUTHENTICATED");
    }

    @Test
    void Bearer_토큰이면_jwtService로_userId를_뽑는다() {
        var resolver = new CurrentUserArgumentResolver(jwtService);
        UUID userId = UUID.randomUUID();
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(servletRequest);
        when(servletRequest.getHeader("Authorization")).thenReturn("Bearer abc.def.ghi");
        when(jwtService.getUserId("abc.def.ghi")).thenReturn(userId);

        Object result = resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result).isEqualTo(userId);
    }
}
