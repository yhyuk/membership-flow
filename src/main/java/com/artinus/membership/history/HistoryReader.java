package com.artinus.membership.history;

import com.artinus.membership.common.ResourceNotFoundException;
import com.artinus.membership.channel.Channel;
import com.artinus.membership.member.Member;
import com.artinus.membership.history.SubscriptionHistory;
import com.artinus.membership.channel.ChannelRepository;
import com.artinus.membership.member.MemberRepository;
import com.artinus.membership.history.SubscriptionHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 이력 조회의 read-only 트랜잭션 책임자.
 *
 * <p>{@link HistoryService}와 분리한 이유는 Phase 4 패턴과 동일하다 — LLM 호출이 길어도
 * DB 커넥션을 잡지 않도록 read-only TX 경계를 짧게 유지하기 위함이다.</p>
 *
 * <p>처리:
 * <ol>
 *   <li>정규화된 phoneNumber로 Member 조회 (없으면 ResourceNotFoundException → 404)</li>
 *   <li>최근 20건 이력 조회 (V1 인덱스 idx_history_member_occurred 활용)</li>
 *   <li>이력에 등장한 channelId 집합으로 채널 메타를 한 번에 조회 (N+1 회피)</li>
 *   <li>{@link Snapshot}으로 LLM 호출 단계로 전달</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class HistoryReader {

    private final MemberRepository memberRepository;
    private final ChannelRepository channelRepository;
    private final SubscriptionHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public Snapshot read(String normalizedPhoneNumber) {
        Member member = memberRepository.findByPhoneNumber(normalizedPhoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Member", normalizedPhoneNumber));

        List<SubscriptionHistory> recent =
                historyRepository.findTop20ByMemberIdOrderByOccurredAtDesc(member.getId());

        // 이력의 channelId 집합으로 채널 메타 일괄 조회 (id, code, name).
        // 채널은 시드 데이터 6건뿐이고 일부만 참조되므로 findAllById가 효율적이다.
        Map<Long, Channel> channelsById = new HashMap<>();
        if (!recent.isEmpty()) {
            List<Long> channelIds = recent.stream().map(SubscriptionHistory::getChannelId).distinct().toList();
            for (Channel ch : channelRepository.findAllById(channelIds)) {
                channelsById.put(ch.getId(), ch);
            }
        }

        return new Snapshot(member, recent, channelsById);
    }

    /**
     * read-only TX에서 산출된 결과 묶음.
     *
     * @param member       회원 (조회 성공이 보장됨)
     * @param histories    최근 20건 이력 (DESC)
     * @param channelsById 이력에 등장한 채널들의 메타 (code/name) 매핑
     */
    public record Snapshot(
            Member member,
            List<SubscriptionHistory> histories,
            Map<Long, Channel> channelsById
    ) {
    }
}
