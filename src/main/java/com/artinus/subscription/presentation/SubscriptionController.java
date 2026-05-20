package com.artinus.subscription.presentation;

import com.artinus.subscription.application.SubscriptionCommand;
import com.artinus.subscription.application.SubscriptionResult;
import com.artinus.subscription.application.SubscriptionService;
import com.artinus.subscription.presentation.dto.SubscriptionRequest;
import com.artinus.subscription.presentation.dto.SubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구독/해지 단일 진입 컨트롤러.
 *
 * <p>ASSIGNMENT 명세대로 휴대폰번호 + 채널 + 변경할 상태를 받아
 * 현재 상태에 따라 가입/해지를 자동 결정한다. 액션 분기는 Service 레이어에서 수행한다.</p>
 *
 * <p>응답 매핑:
 * <ul>
 *   <li>SUBSCRIBE 액션 → 201 Created</li>
 *   <li>UNSUBSCRIBE 액션 → 200 OK</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/v1/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(
            summary = "구독/해지",
            description = "휴대폰 번호, 채널 코드, 변경할 구독 상태를 받아 현재 상태에 맞춰 가입/해지를 수행한다. " +
                    "외부 csrng API 응답에 따라 트랜잭션이 커밋(random=1) 또는 거부(random=0)된다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해지 성공 (UNSUBSCRIBE)",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
            @ApiResponse(responseCode = "201", description = "가입 성공 (SUBSCRIBE)",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력 유효성 위반 (VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "회원/채널 미존재 (RESOURCE_NOT_FOUND)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "동시 수정 충돌 (CONCURRENT_MODIFICATION)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "상태 전이/채널 정책/외부 검증 거부",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "csrng 외부 API 장애 (EXTERNAL_API_UNAVAILABLE)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubscriptionResponse> submit(@Valid @RequestBody SubscriptionRequest request) {
        SubscriptionResult result = subscriptionService.execute(new SubscriptionCommand(
                request.phoneNumber(),
                request.channelCode(),
                request.targetState()
        ));

        SubscriptionResponse body = SubscriptionResponse.from(result);
        return switch (result.actionType()) {
            case SUBSCRIBE -> ResponseEntity.status(201).body(body);
            case UNSUBSCRIBE -> ResponseEntity.ok(body);
        };
    }
}
