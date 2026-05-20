package com.artinus.subscription.presentation.dto;

import com.artinus.subscription.domain.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 구독/해지 요청 페이로드.
 *
 * <p>ASSIGNMENT 명세(line 67, 81)의 입력 3종(휴대폰번호, 채널ID, 변경할 구독 상태)을
 * 정확히 반영한다. {@code channelCode}는 채널 식별 코드 문자열(예: HOMEPAGE).</p>
 *
 * <p>액션(SUBSCRIBE vs UNSUBSCRIBE)은 별도 필드로 받지 않고, 컨트롤러에서
 * 요청 메타(엔드포인트) 기준으로 결정되거나 본 DTO의 {@code targetState}와
 * 채널 권한을 조합하여 유추한다. 본 과제에서는 ASSIGNMENT 명세를 그대로 따라
 * {@code targetState} 한 필드로 가입/해지를 모두 표현한다.</p>
 */
@Schema(description = "구독/해지 요청. ASSIGNMENT 명세에 따라 휴대폰 번호, 채널 코드, 변경할 구독 상태를 받는다.")
public record SubscriptionRequest(

        @Schema(
                description = "휴대폰 번호. 국내 형식 `010` + 8자리 숫자(총 11자리). " +
                        "하이픈/공백 등은 입력 시점에서는 허용하지 않는다(컨트롤러는 raw 검증, " +
                        "서비스 진입 전 정규화 시에는 별도 클라이언트 또는 운영자 전처리 가정).",
                example = "01012345678",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "phoneNumber must not be blank")
        @Pattern(regexp = "^010\\d{8}$", message = "phoneNumber must match ^010\\d{8}$")
        String phoneNumber,

        @Schema(
                description = "채널 식별 코드. 시드 데이터에 정의된 6종 중 하나.",
                example = "HOMEPAGE",
                allowableValues = {"HOMEPAGE", "MOBILE_APP", "NAVER", "SKT", "CALL_CENTER", "EMAIL"},
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "channelCode must not be blank")
        String channelCode,

        @Schema(
                description = "변경할 구독 상태. NONE은 해지, BASIC/PREMIUM은 가입/업그레이드/다운그레이드를 의미.",
                example = "BASIC",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "targetState must not be null")
        SubscriptionState targetState
) {
}
