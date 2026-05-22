package com.artinus.membership.subscription;

import com.artinus.membership.common.dto.ApiResponse;
import com.artinus.membership.subscription.application.SubscriptionService;
import com.artinus.membership.subscription.dto.SubscriptionRequest;
import com.artinus.membership.subscription.dto.SubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 구독/해지 단일 진입 컨트롤러. 응답은 {@link ApiResponse} 래퍼 사용. */
@RestController
@RequestMapping(value = "/api/v1/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(
            summary = "구독/해지",
            description = """
                    회원의 구독 상태를 변경하는 API (가입/등급 변경/해지 통합).

                    - targetState : NONE(해지), BASIC(일반), PREMIUM(프리미엄)
                    - channelCode : HOMEPAGE, MOBILE_APP, NAVER, SKT, CALL_CENTER, EMAIL
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공 (SUCCESS)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "RESOURCE_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "CONCURRENT_MODIFICATION",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "INVALID_STATE_TRANSITION / CHANNEL_POLICY_VIOLATION / EXTERNAL_VALIDATION_REJECTED",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "EXTERNAL_API_UNAVAILABLE",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500", description = "INTERNAL_ERROR",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SubscriptionResponse>> submit(@Valid @RequestBody SubscriptionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.execute(request)));
    }
}
