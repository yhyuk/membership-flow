package com.artinus.membership.history.application;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.channel.ChannelRepository;
import com.artinus.membership.common.exception.ResourceNotFoundException;
import com.artinus.membership.history.domain.SubscriptionHistory;
import com.artinus.membership.history.persistence.SubscriptionHistoryRepository;
import com.artinus.membership.member.Member;
import com.artinus.membership.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 이력 조회의 read-only 트랜잭션 담당.
 * Service와 별도 빈으로 둔 이유: {@code @Transactional} self-invocation 회피 + LLM 호출이 TX 밖에서 일어나도록 경계 분리.
 *
 * <p>채널 메타는 등장한 channelId만 한 번에 조회해 N+1을 회피.
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

        Map<Long, Channel> channelsById = new HashMap<>();
        if (!recent.isEmpty()) {
            List<Long> channelIds = recent.stream().map(SubscriptionHistory::getChannelId).distinct().toList();
            for (Channel ch : channelRepository.findAllById(channelIds)) {
                channelsById.put(ch.getId(), ch);
            }
        }

        return new Snapshot(member, recent, channelsById);
    }

    public record Snapshot(
            Member member,
            List<SubscriptionHistory> histories,
            Map<Long, Channel> channelsById
    ) {
    }
}
