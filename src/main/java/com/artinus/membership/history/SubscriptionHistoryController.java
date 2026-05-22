package com.artinus.membership.history;

import com.artinus.membership.common.dto.ApiResponse;
import com.artinus.membership.history.application.HistoryService;
import com.artinus.membership.history.dto.SubscriptionHistoryResponse;
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

/** 회원 구독 이력 조회. LLM 장애 시 HTTP 200 + status=DEGRADED. */
@RestController
@RequestMapping(value = "/api/v1/members", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubscriptionHistoryController {

    private final HistoryService historyService;

    @Operation(
            summary = "회원 구독 이력 조회 + LLM 요약",
            description = """
                    회원의 최근 구독/해지 이력과 LLM이 생성한 자연어 요약 조회.

                    data.status : NORMAL / DEGRADED / EMPTY
                    LLM 실패 시에도 HTTP 200으로 응답하며 data.summary=null.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공 (data.status: NORMAL/DEGRADED/EMPTY)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "RESOURCE_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500", description = "INTERNAL_ERROR",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/{phoneNumber}/subscription-histories")
    public ResponseEntity<ApiResponse<SubscriptionHistoryResponse>> getHistories(
            @PathVariable("phoneNumber") String phoneNumber) {
        return ResponseEntity.ok(ApiResponse.success(historyService.getRecentHistories(phoneNumber)));
    }
}
