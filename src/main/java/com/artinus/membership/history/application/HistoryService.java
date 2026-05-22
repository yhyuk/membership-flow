package com.artinus.membership.history.application;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.history.domain.SubscriptionHistory;
import com.artinus.membership.history.dto.SubscriptionHistoryResponse;
import com.artinus.membership.llm.GeminiClient;
import com.artinus.membership.llm.GeminiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 이력 조회 + LLM 요약 오케스트레이터.
 *
 * <ol>
 *   <li>{@link HistoryReader#read} — read-only TX에서 회원/이력/채널 메타 조회</li>
 *   <li>{@link GeminiClient#summarize} — TX 밖에서 LLM 호출. 실패 시 status=DEGRADED로 흡수(HTTP 200 유지)</li>
 * </ol>
 *
 * <p>이력 0건이면 LLM 호출을 건너뛰고 EMPTY로 응답.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^010\\d{8}$");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    private final HistoryReader historyReader;
    private final GeminiClient geminiClient;
    private final Clock clock;

    public SubscriptionHistoryResponse getRecentHistories(String rawPhoneNumber) {
        String normalized = normalize(rawPhoneNumber);
        HistoryReader.Snapshot snapshot = historyReader.read(normalized);

        if (snapshot.histories().isEmpty()) {
            return toResponse(snapshot, null, SubscriptionHistoryResponse.Status.EMPTY);
        }

        Map<Long, String> channelCodeById = toChannelCodeMap(snapshot.channelsById());
        try {
            String summary = geminiClient.summarize(snapshot.histories(), channelCodeById);
            return toResponse(snapshot, summary, SubscriptionHistoryResponse.Status.NORMAL);
        } catch (GeminiException | HttpServerErrorException | ResourceAccessException e) {
            // LLM 장애는 응답을 막지 않는다 — status=DEGRADED + summary=null, HTTP 200.
            log.warn("LLM summarization failed, returning DEGRADED. reason={}", e.toString());
            return toResponse(snapshot, null, SubscriptionHistoryResponse.Status.DEGRADED);
        }
    }

    private SubscriptionHistoryResponse toResponse(
            HistoryReader.Snapshot snapshot,
            String summary,
            SubscriptionHistoryResponse.Status status) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        List<SubscriptionHistoryResponse.HistoryItem> items = snapshot.histories().stream()
                .map(h -> toItem(h, snapshot.channelsById()))
                .toList();
        return new SubscriptionHistoryResponse(
                snapshot.member().getId(),
                maskPhoneNumber(snapshot.member().getPhoneNumber()),
                items,
                summary,
                status,
                now);
    }

    private static SubscriptionHistoryResponse.HistoryItem toItem(
            SubscriptionHistory h, Map<Long, Channel> channelsById) {
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

    /** 01012345678 → 010-****-5678 */
    static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() != 11) {
            return phoneNumber;
        }
        return phoneNumber.substring(0, 3) + "-****-" + phoneNumber.substring(7);
    }

    /** 비숫자 제거 후 ^010\d{8}$ 매칭. */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("phoneNumber must not be blank");
        }
        String digitsOnly = NON_DIGIT.matcher(raw).replaceAll("");
        if (!PHONE_PATTERN.matcher(digitsOnly).matches()) {
            throw new IllegalArgumentException("phoneNumber must match ^010\\d{8}$ after normalization");
        }
        return digitsOnly;
    }

    private static Map<Long, String> toChannelCodeMap(Map<Long, Channel> channelsById) {
        Map<Long, String> result = new HashMap<>(channelsById.size());
        channelsById.forEach((id, channel) -> result.put(id, channel.getCode()));
        return result;
    }
}
