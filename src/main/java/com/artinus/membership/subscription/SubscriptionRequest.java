package com.artinus.membership.subscription;

import com.artinus.membership.subscription.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 구독/해지 요청 페이로드.
 */
@Schema(name = "SubscriptionRequest", description = "구독/해지 요청")
public record SubscriptionRequest(

        @Schema(
                description = "휴대폰 번호 (국내 형식 `010` + 8자리, 총 11자리)",
                example = "01012345678",
                pattern = "^010\\d{8}$",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "phoneNumber must not be blank")
        @Pattern(regexp = "^010\\d{8}$", message = "phoneNumber must match ^010\\d{8}$")
        String phoneNumber,

        @Schema(
                description = "채널 코드 — `HOMEPAGE`: 홈페이지(구독/해지), `MOBILE_APP`: 모바일앱(구독/해지), " +
                        "`NAVER`: 네이버(구독 전용), `SKT`: SKT(구독 전용), " +
                        "`CALL_CENTER`: 콜센터(해지 전용), `EMAIL`: 이메일(해지 전용)",
                example = "HOMEPAGE",
                allowableValues = {"HOMEPAGE", "MOBILE_APP", "NAVER", "SKT", "CALL_CENTER", "EMAIL"},
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "channelCode must not be blank")
        String channelCode,

        @Schema(
                description = "변경할 구독 상태 — `NONE`: 구독 안함(해지), `BASIC`: 일반 구독, `PREMIUM`: 프리미엄 구독",
                example = "BASIC",
                allowableValues = {"NONE", "BASIC", "PREMIUM"},
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "targetState must not be null")
        SubscriptionState targetState
) {
}
