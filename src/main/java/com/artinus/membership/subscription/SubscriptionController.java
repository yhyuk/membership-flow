package com.artinus.membership.subscription;

import com.artinus.membership.subscription.SubscriptionService;
import com.artinus.membership.subscription.SubscriptionRequest;
import com.artinus.membership.subscription.SubscriptionResponse;
import com.artinus.membership.common.ApiResponse;
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

/**
 * 구독/해지 단일 진입 컨트롤러.
 *
 * <p>모든 응답은 {@link ApiResponse} 래퍼로 감싸진다. 정상 응답은 HTTP 200 + code=SUCCESS.
 * 가입/해지 분기는 클라이언트가 {@code data.actionType} 등으로 식별한다 (필요 시).</p>
 */
@RestController
@RequestMapping(value = "/api/v1/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(
            summary = "구독/해지",
            description = """
                    ### 회원의 구독 상태를 변경하는 API (가입/등급 변경/해지 통합)

                    - targetState : NONE(구독 안함), BASIC(일반 구독), PREMIUM(프리미엄 구독)
                    - channelCode : HOMEPAGE(홈페이지), MOBILE_APP(모바일앱), NAVER(네이버), SKT(SKT), CALL_CENTER(콜센터), EMAIL(이메일)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "구독/해지 처리 성공 (code=SUCCESS)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "입력 유효성 위반 (VALIDATION_FAILED)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "회원/채널 미존재 (RESOURCE_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "동시 수정 충돌 (CONCURRENT_MODIFICATION)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "상태 전이/채널 정책/외부 검증 거부",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "csrng 외부 API 장애 (EXTERNAL_API_UNAVAILABLE)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500", description = "서버 내부 오류 (INTERNAL_ERROR)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SubscriptionResponse>> submit(@Valid @RequestBody SubscriptionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.execute(request)));
    }
}
