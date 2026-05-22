package com.artinus.membership.history;

import com.artinus.membership.history.HistoryService;
import com.artinus.membership.history.SubscriptionHistoryResponse;
import com.artinus.membership.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 구독 이력 조회 컨트롤러.
 *
 * <p>모든 응답은 {@link ApiResponse} 래퍼로 감싸지며, 정상 응답은 HTTP 200 + code=SUCCESS.
 * LLM 장애로 인한 DEGRADED 신호는 {@code data.status} 필드에 담는다 (HTTP 상태와 분리).</p>
 */
@RestController
@RequestMapping(value = "/api/v1/members", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubscriptionHistoryController {

    private final HistoryService historyService;

    @Operation(
            summary = "회원 구독 이력 조회 + LLM 요약",
            description = """
                    ### 회원의 최근 구독/해지 이력과 LLM이 생성한 자연어 요약을 조회하는 API

                    - data.status : NORMAL(정상 요약 생성), DEGRADED(LLM 호출 실패로 요약 없음), EMPTY(이력 0건)
                    - LLM 호출 실패 시에도 HTTP 200 + code=SUCCESS로 응답하며 data.summary=null
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공 (data.status는 NORMAL/DEGRADED/EMPTY 중 하나)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "phoneNumber 형식 위반 (VALIDATION_FAILED)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "회원 미존재 (RESOURCE_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500", description = "서버 내부 오류 (INTERNAL_ERROR)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/{phoneNumber}/subscription-histories")
    public ResponseEntity<ApiResponse<SubscriptionHistoryResponse>> getHistories(
            @PathVariable("phoneNumber") String phoneNumber) {
        return ResponseEntity.ok(ApiResponse.success(historyService.getRecentHistories(phoneNumber)));
    }
}
