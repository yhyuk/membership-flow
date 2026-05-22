package com.artinus.membership.history;

import com.artinus.membership.common.ResourceNotFoundException;
import com.artinus.membership.history.SubscriptionHistoryResponse;
import com.artinus.membership.channel.Channel;
import com.artinus.membership.member.Member;
import com.artinus.membership.history.SubscriptionHistory;
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
 * <p><b>2-Phase 구조</b> (Phase 4 패턴과 동일):
 * <ol>
 *   <li>{@link HistoryReader#read} — read-only 트랜잭션에서 회원/이력/채널 메타 일괄 조회.</li>
 *   <li>{@link GeminiClient#summarize} — 트랜잭션 <b>밖</b>에서 LLM 호출. 실패해도 DB 커넥션 미점유.</li>
 * </ol>
 * 본 서비스에는 {@code @Transactional}이 없다. 그 결과 LLM 응답이 느려도 DB 커넥션이 잡히지 않는다.</p>
 *
 * <p><b>예외 처리</b>:
 * <ul>
 *   <li>phoneNumber 정규식 위반 → {@link IllegalArgumentException} → GlobalExceptionHandler 500/혹은 명시 400.
 *       (Controller가 @Pattern path-param 사용 시 ConstraintViolation으로 400 처리 가능. 본 서비스는
 *       방어적으로 raw 입력을 정규화한 뒤 재검증한다.)</li>
 *   <li>Member 미존재 → {@code ResourceNotFoundException} → 404 (HistoryReader에서 throw).</li>
 *   <li>LLM 호출 실패(GeminiException / 5xx / timeout) → catch 후 status=DEGRADED + summary=null로 degrade.
 *       <b>외부로 502 전파되지 않는다.</b> 이 결정은 사용자 명시: "LLM 장애 응답은 HTTP 200, status=DEGRADED".</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    /** 국내 휴대폰 번호 정규식 — handoff §3.4 ({@code ^010\d{8}$}). */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^010\\d{8}$");

    /** 비숫자 모두 제거용. */
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    private final HistoryReader historyReader;
    private final GeminiClient geminiClient;
    private final Clock clock;

    /**
     * 회원의 최근 20건 이력 + LLM 요약을 반환한다.
     *
     * @param rawPhoneNumber 클라이언트가 전달한 원본 휴대폰 번호 (하이픈 포함 가능)
     * @return 회원/이력/요약/상태가 묶인 응답
     * @throws IllegalArgumentException                                       정규화 후에도 형식이 맞지 않을 때
     * @throws com.artinus.membership.common.ResourceNotFoundException Member 미존재 (404)
     */
    public SubscriptionHistoryResponse getRecentHistories(String rawPhoneNumber) {
        String normalized = normalize(rawPhoneNumber);

        // 1단계 — read-only TX에서 회원/이력/채널 메타 조회.
        HistoryReader.Snapshot snapshot = historyReader.read(normalized);

        // 이력이 0건이면 LLM 호출 자체를 건너뛴다 (불필요 호출 + 비용 방지).
        if (snapshot.histories().isEmpty()) {
            return toResponse(snapshot, null, SubscriptionHistoryResponse.Status.EMPTY);
        }

        // 2단계 — TX 밖에서 LLM 호출. 어떤 실패도 DEGRADED로 흡수.
        Map<Long, String> channelCodeById = toChannelCodeMap(snapshot.channelsById());
        try {
            String summary = geminiClient.summarize(snapshot.histories(), channelCodeById);
            return toResponse(snapshot, summary, SubscriptionHistoryResponse.Status.NORMAL);
        } catch (GeminiException | HttpServerErrorException | ResourceAccessException e) {
            // LLM 장애는 응답을 막지 않는다 — handoff에 따라 status=DEGRADED + summary=null, HTTP 200.
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

    /**
     * 휴대폰 번호 마스킹: 11자리 입력에서 가운데 4자리를 ****로 치환하고 dash 포맷팅을 적용한다.
     * 예: 01012345678 → 010-****-5678
     */
    static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() != 11) {
            return phoneNumber;
        }
        return phoneNumber.substring(0, 3) + "-****-" + phoneNumber.substring(7);
    }

    /**
     * 휴대폰 번호 정규화 + 검증. handoff §3.4 규칙대로 비숫자 제거 후 ^010\d{8}$ 매칭.
     *
     * @throws IllegalArgumentException 정규화 후에도 형식이 맞지 않을 때
     */
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

    /** channelId → channelCode 매핑 추출 (LLM 프롬프트용). */
    private static Map<Long, String> toChannelCodeMap(Map<Long, Channel> channelsById) {
        Map<Long, String> result = new HashMap<>(channelsById.size());
        channelsById.forEach((id, channel) -> result.put(id, channel.getCode()));
        return result;
    }
}
