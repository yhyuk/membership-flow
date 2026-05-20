package com.artinus.subscription.presentation;

import com.artinus.subscription.application.HistoryResult;
import com.artinus.subscription.application.HistoryService;
import com.artinus.subscription.domain.Channel;
import com.artinus.subscription.domain.SubscriptionHistory;
import com.artinus.subscription.presentation.dto.SubscriptionHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 회원 구독 이력 조회 컨트롤러.
 *
 * <p>ASSIGNMENT 명세(§3): 휴대폰번호로 회원의 구독/해지 이력 + LLM 요약을 반환.
 * 본 과제 결정상 단일 응답에 history + summary + status가 함께 들어가며, LLM 장애 시에도
 * HTTP 200으로 응답하고 status=DEGRADED로 신호한다.</p>
 *
 * <p>경로 변수 {@code phoneNumber}는 controller에서 별도 검증하지 않고
 * {@link HistoryService#normalize(String)}이 정규화/검증을 수행한다. 형식 위반은
 * {@code IllegalArgumentException} → {@code GlobalExceptionHandler}에서 400으로 매핑.</p>
 */
@RestController
@RequestMapping(value = "/api/v1/members", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubscriptionHistoryController {

    private final HistoryService historyService;
    private final Clock clock;

    @Operation(
            summary = "회원 구독 이력 조회 + LLM 요약",
            description = "회원의 최근 20건 이력과 LLM이 생성한 자연어 요약을 함께 반환한다. " +
                    "LLM 호출 실패 시에도 HTTP 200으로 응답하고 status=DEGRADED, summary=null로 신호한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (NORMAL/DEGRADED/EMPTY 중 하나)",
                    content = @Content(schema = @Schema(implementation = SubscriptionHistoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "phoneNumber 형식 위반 (VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "회원 미존재 (RESOURCE_NOT_FOUND)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{phoneNumber}/subscription-histories")
    public ResponseEntity<SubscriptionHistoryResponse> getHistories(@PathVariable("phoneNumber") String phoneNumber) {
        HistoryResult result = historyService.getRecentHistories(phoneNumber);
        return ResponseEntity.ok(toResponse(result));
    }

    private SubscriptionHistoryResponse toResponse(HistoryResult result) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        List<SubscriptionHistoryResponse.HistoryItem> items = result.histories().stream()
                .map(h -> toItem(h, result.channelsById()))
                .toList();
        return new SubscriptionHistoryResponse(
                result.member().getId(),
                maskPhoneNumber(result.member().getPhoneNumber()),
                items,
                result.summary(),
                result.status().name(),
                now);
    }

    private static SubscriptionHistoryResponse.HistoryItem toItem(SubscriptionHistory h, Map<Long, Channel> channelsById) {
        Channel channel = channelsById.get(h.getChannelId());
        String code = channel == null ? "UNKNOWN" : channel.getCode();
        String name = channel == null ? "UNKNOWN" : channel.getName();
        return new SubscriptionHistoryResponse.HistoryItem(
                h.getOccurredAt(),
                code,
                name,
                h.getPreviousState(),
                h.getNextState(),
                h.getEventType());
    }

    /**
     * 휴대폰 번호 마스킹: 11자리 입력에서 가운데 4자리를 ****로 치환하고 dash 포맷팅을 적용한다.
     * 예: 01012345678 → 010-****-5678
     *
     * <p>입력이 비정상이면 원본을 그대로 반환한다 (방어적).</p>
     */
    static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() != 11) {
            return phoneNumber;
        }
        return phoneNumber.substring(0, 3) + "-****-" + phoneNumber.substring(7);
    }
}
