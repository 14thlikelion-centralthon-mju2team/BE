package com.hq.backend.user;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.dto.AccountDeletionResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// DATA-01/02 탈퇴(§16) — 회원 대부분의 테이블이 users(user_id)에 ON DELETE CASCADE로
// 걸려 있어(V6 마이그레이션) DB가 하드 삭제 전파를 대신 해준다. USER_CONSENT만
// ON DELETE SET NULL이라 행이 남는다(법정 보존). 그래서 서비스 계층은 users 행 하나만
// 지우면 되고, 응답의 deleted/retained 목록은 그 스키마 계약을 그대로 문서화한 상수다.
@Service
@RequiredArgsConstructor
public class UserService {

    private static final List<String> DELETED_RESOURCES = List.of(
            "events", "plans", "prepRules", "places", "calendarConnections",
            "pushDevices", "actionLogs", "prepEstimates", "wellnessPrefs",
            "identities", "credential", "authTokens");
    private static final List<String> RETAINED_RESOURCES = List.of("consentHistory");
    private static final String RETENTION_REASON = "법정 보존 의무";

    private final UserRepository userRepository;

    @Transactional
    public AccountDeletionResponse withdraw(UUID userId) {
        // accountStatus/withdrawnAt를 세팅하지 않는다 — 같은 트랜잭션에서 바로 delete()하므로
        // users 행 자체가 사라져 그 값은 커밋 후 어디에도 남지 않는다(parkc31 리뷰, PR #82).
        // 하드 삭제·익명화 배치 없음이 명시된 설계(D10)라 별도 감사 기록도 필요 없다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        userRepository.delete(user);

        return new AccountDeletionResponse(DELETED_RESOURCES, RETAINED_RESOURCES, RETENTION_REASON);
    }
}
