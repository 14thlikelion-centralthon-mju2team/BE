package com.hq.backend.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 02-api-spec.md 공통 에러 계약({status} {CODE})을 코드로 표현하는 범용 예외.
 * 엔드포인트별 서브클래스 없이 status/code/message만으로 응답을 구성한다.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
