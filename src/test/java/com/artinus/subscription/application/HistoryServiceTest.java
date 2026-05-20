package com.artinus.subscription.application;

import com.artinus.subscription.application.exception.ResourceNotFoundException;
import com.artinus.subscription.domain.Channel;
import com.artinus.subscription.domain.Member;
import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.SubscriptionHistory;
import com.artinus.subscription.domain.SubscriptionState;
import com.artinus.subscription.external.llm.GeminiClient;
import com.artinus.subscription.external.llm.GeminiException;
import com.artinus.subscription.infrastructure.repository.ChannelRepository;
import com.artinus.subscription.infrastructure.repository.MemberRepository;
import com.artinus.subscription.infrastructure.repository.SubscriptionHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpServerErrorException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HistoryService} 단위 테스트.
 *
 * <p>HistoryReader를 직접 생성해 주입하여 read 단계의 실 동작을 검증한다.
 * GeminiClient만 모킹하여 status=NORMAL/DEGRADED/EMPTY 분기를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    private static final String PHONE = "01012345678";
    private static final String PHONE_WITH_DASH = "010-1234-5678";

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private SubscriptionHistoryRepository historyRepository;
    @Mock
    private GeminiClient geminiClient;

    private HistoryService historyService;

    @BeforeEach
    void setUp() {
        HistoryReader reader = new HistoryReader(memberRepository, channelRepository, historyRepository);
        historyService = new HistoryService(reader, geminiClient);
    }

    @Test
    @DisplayName("정상 이력 + LLM 성공 → status=NORMAL, summary != null")
    void normalCase() {
        Member member = member(57L);
        Channel homepage = channel(1L, "HOMEPAGE", "홈페이지");
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(member));
        when(historyRepository.findTop20ByMemberIdOrderByOccurredAtDesc(57L))
                .thenReturn(List.of(history(member.getId(), homepage.getId())));
        when(channelRepository.findAllById(anyList()))
                .thenReturn(List.of(homepage));
        when(geminiClient.summarize(anyList(), anyMap()))
                .thenReturn("요약 결과");

        HistoryResult result = historyService.getRecentHistories(PHONE);

        assertThat(result.status()).isEqualTo(HistoryResult.Status.NORMAL);
        assertThat(result.summary()).isEqualTo("요약 결과");
        assertThat(result.histories()).hasSize(1);
    }

    @Test
    @DisplayName("정상 이력 + LLM 5xx (HttpServerErrorException) → status=DEGRADED")
    void degradedOn5xx() {
        Member member = member(57L);
        Channel homepage = channel(1L, "HOMEPAGE", "홈페이지");
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(member));
        when(historyRepository.findTop20ByMemberIdOrderByOccurredAtDesc(57L))
                .thenReturn(List.of(history(member.getId(), homepage.getId())));
        when(channelRepository.findAllById(anyList())).thenReturn(List.of(homepage));
        when(geminiClient.summarize(anyList(), anyMap()))
                .thenThrow(HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable", null, null, null));

        HistoryResult result = historyService.getRecentHistories(PHONE);

        assertThat(result.status()).isEqualTo(HistoryResult.Status.DEGRADED);
        assertThat(result.summary()).isNull();
        assertThat(result.histories()).hasSize(1);
    }

    @Test
    @DisplayName("정상 이력 + GeminiException(api-key 미설정 모방) → status=DEGRADED")
    void degradedOnApiKeyMissing() {
        Member member = member(57L);
        Channel homepage = channel(1L, "HOMEPAGE", "홈페이지");
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(member));
        when(historyRepository.findTop20ByMemberIdOrderByOccurredAtDesc(57L))
                .thenReturn(List.of(history(member.getId(), homepage.getId())));
        when(channelRepository.findAllById(anyList())).thenReturn(List.of(homepage));
        when(geminiClient.summarize(anyList(), anyMap()))
                .thenThrow(new GeminiException("api-key not configured"));

        HistoryResult result = historyService.getRecentHistories(PHONE);

        assertThat(result.status()).isEqualTo(HistoryResult.Status.DEGRADED);
        assertThat(result.summary()).isNull();
    }

    @Test
    @DisplayName("빈 이력 → status=EMPTY, LLM 호출 안 함")
    void emptyHistoriesSkipsLlm() {
        Member member = member(57L);
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(member));
        when(historyRepository.findTop20ByMemberIdOrderByOccurredAtDesc(57L))
                .thenReturn(List.of());

        HistoryResult result = historyService.getRecentHistories(PHONE);

        assertThat(result.status()).isEqualTo(HistoryResult.Status.EMPTY);
        assertThat(result.summary()).isNull();
        assertThat(result.histories()).isEmpty();
        // 이력이 없을 때는 LLM에 빈 컨텍스트를 보내지 않아야 한다 (불필요 비용 회피).
        verify(geminiClient, never()).summarize(anyList(), anyMap());
        // 채널 메타 조회도 발생하지 않아야 한다.
        verify(channelRepository, never()).findAllById(anyList());
    }

    @Test
    @DisplayName("Member 미존재 → ResourceNotFoundException, LLM 호출 안 함")
    void memberNotFoundPropagatesAs404() {
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> historyService.getRecentHistories(PHONE))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(geminiClient, never()).summarize(anyList(), anyMap());
    }

    @Test
    @DisplayName("phoneNumber 형식 오류 → IllegalArgumentException")
    void invalidPhoneNumberThrows() {
        assertThatThrownBy(() -> historyService.getRecentHistories("invalid"))
                .isInstanceOf(IllegalArgumentException.class);

        // 정규화 단계에서 거부되므로 어떤 DB/LLM 호출도 발생하지 않아야 한다.
        verify(memberRepository, never()).findByPhoneNumber(any());
        verify(geminiClient, never()).summarize(anyList(), anyMap());
    }

    @Test
    @DisplayName("phoneNumber 하이픈 포함 입력 → 정규화 후 처리 (handoff §3.4)")
    void normalizesPhoneWithDashes() {
        Member member = member(57L);
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(member));
        when(historyRepository.findTop20ByMemberIdOrderByOccurredAtDesc(57L))
                .thenReturn(List.of());

        HistoryResult result = historyService.getRecentHistories(PHONE_WITH_DASH);

        // 비숫자가 제거된 정규형으로 조회되어야 함.
        verify(memberRepository).findByPhoneNumber(eq(PHONE));
        assertThat(result.status()).isEqualTo(HistoryResult.Status.EMPTY);
    }

    // ---------- 헬퍼 ----------

    private static Member member(long id) {
        LocalDateTime now = LocalDateTime.of(2026, 5, 20, 12, 0);
        return Member.builder()
                .id(id)
                .phoneNumber(PHONE)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static Channel channel(long id, String code, String name) {
        return Channel.builder()
                .id(id).code(code).name(name)
                .subscribable(true).unsubscribable(true)
                .createdAt(LocalDateTime.of(2026, 5, 20, 12, 0))
                .build();
    }

    private static SubscriptionHistory history(long memberId, long channelId) {
        return SubscriptionHistory.builder()
                .id(1L)
                .subscriptionId(100L)
                .memberId(memberId)
                .channelId(channelId)
                .previousState(SubscriptionState.NONE)
                .nextState(SubscriptionState.BASIC)
                .eventType(StateTransitionEvent.ActionType.SUBSCRIBE)
                .occurredAt(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build();
    }
}
