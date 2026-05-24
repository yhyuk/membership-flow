package com.artinus.membership.subscription;

import com.artinus.membership.common.exception.IllegalStateTransitionException;

import com.artinus.membership.subscription.application.SubscriptionApplier;
import com.artinus.membership.subscription.application.SubscriptionService;
import com.artinus.membership.subscription.application.SubscriptionValidator;
import com.artinus.membership.subscription.dto.SubscriptionRequest;
import com.artinus.membership.subscription.dto.SubscriptionResponse;
import com.artinus.membership.common.exception.ChannelPolicyViolationException;
import com.artinus.membership.common.exception.ConcurrentModificationException;
import com.artinus.membership.common.exception.ExternalValidationRejectedException;
import com.artinus.membership.common.exception.ResourceNotFoundException;
import com.artinus.membership.channel.Channel;
import com.artinus.membership.member.Member;
import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.Subscription;
import com.artinus.membership.history.domain.SubscriptionHistory;
import com.artinus.membership.subscription.domain.SubscriptionState;
import com.artinus.membership.csrng.CsrngClient;
import com.artinus.membership.csrng.CsrngException;
import com.artinus.membership.channel.ChannelRepository;
import com.artinus.membership.member.MemberRepository;
import com.artinus.membership.history.persistence.SubscriptionHistoryRepository;
import com.artinus.membership.subscription.persistence.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SubscriptionService} 단위 테스트.
 *
 * <p>2-Phase TX 구조 검증 — csrng 호출이 검증/적용 사이에 일어나는지,
 * random=0 또는 csrng 장애 시 적용 단계가 실행되지 않는지 확인한다.</p>
 *
 * <p>본 테스트는 Validator/Applier를 직접 생성하여 SubscriptionService에 주입한다.
 * 두 Bean을 따로 mock하면 검증 로직 자체가 누락되어 의미가 줄어들기 때문이다.
 * Repository와 CsrngClient만 모킹한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final String PHONE = "01012345678";
    private static final String CHANNEL_CODE = "HOMEPAGE";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 5, 20, 12, 0);

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private SubscriptionHistoryRepository subscriptionHistoryRepository;
    @Mock
    private CsrngClient csrngClient;

    private SubscriptionService subscriptionService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(
                FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        SubscriptionValidator validator = new SubscriptionValidator(
                memberRepository, channelRepository, subscriptionRepository);
        SubscriptionApplier applier = new SubscriptionApplier(
                memberRepository, subscriptionRepository, subscriptionHistoryRepository);
        subscriptionService = new SubscriptionService(validator, applier, csrngClient, clock);
    }

    @Test
    @DisplayName("신규 회원 BASIC 가입 성공 — Member INSERT, Subscription INSERT, History INSERT 모두 1회")
    void newMemberSubscribeBasic() {
        Channel channel = homepageChannel();
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        Member savedMember = memberWithId(10L);
        when(memberRepository.save(any(Member.class))).thenReturn(savedMember);
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 200L));
        when(csrngClient.fetchRandomBit()).thenReturn(1);

        SubscriptionResponse result = subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.BASIC));

        assertThat(result.previousState()).isEqualTo(SubscriptionState.NONE);
        assertThat(result.currentState()).isEqualTo(SubscriptionState.BASIC);
        assertThat(result.actionType()).isEqualTo(StateTransitionEvent.ActionType.SUBSCRIBE);
        assertThat(result.occurredAt()).isEqualTo(FIXED_NOW);
        verify(memberRepository).save(any(Member.class));
        verify(subscriptionRepository).save(any(Subscription.class));
        verify(subscriptionHistoryRepository).save(any(SubscriptionHistory.class));
    }

    @Test
    @DisplayName("기존 회원 BASIC → PREMIUM 업그레이드 성공")
    void existingMemberUpgradeToPremium() {
        Channel channel = homepageChannel();
        Member existing = memberWithId(10L);
        Subscription existingSub = subscriptionWith(10L, channel.getId(), SubscriptionState.BASIC);
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.of(existingSub));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 200L));
        when(csrngClient.fetchRandomBit()).thenReturn(1);

        SubscriptionResponse result = subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.PREMIUM));

        assertThat(result.previousState()).isEqualTo(SubscriptionState.BASIC);
        assertThat(result.currentState()).isEqualTo(SubscriptionState.PREMIUM);
        assertThat(result.actionType()).isEqualTo(StateTransitionEvent.ActionType.SUBSCRIBE);
        verify(memberRepository, never()).save(any(Member.class));
    }

    @Test
    @DisplayName("PREMIUM → BASIC 다운그레이드 해지 성공 (UNSUBSCRIBE_BASIC)")
    void downgradePremiumToBasic() {
        Channel channel = homepageChannel();
        Member existing = memberWithId(10L);
        Subscription existingSub = subscriptionWith(10L, channel.getId(), SubscriptionState.PREMIUM);
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.of(existingSub));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 200L));
        when(csrngClient.fetchRandomBit()).thenReturn(1);

        SubscriptionResponse result = subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.BASIC));

        assertThat(result.previousState()).isEqualTo(SubscriptionState.PREMIUM);
        assertThat(result.currentState()).isEqualTo(SubscriptionState.BASIC);
        assertThat(result.actionType()).isEqualTo(StateTransitionEvent.ActionType.UNSUBSCRIBE);
    }

    @Test
    @DisplayName("PREMIUM → NONE 완전 해지 성공")
    void cancelPremiumToNone() {
        Channel channel = homepageChannel();
        Member existing = memberWithId(10L);
        Subscription existingSub = subscriptionWith(10L, channel.getId(), SubscriptionState.PREMIUM);
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.of(existingSub));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 200L));
        when(csrngClient.fetchRandomBit()).thenReturn(1);

        SubscriptionResponse result = subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.NONE));

        assertThat(result.previousState()).isEqualTo(SubscriptionState.PREMIUM);
        assertThat(result.currentState()).isEqualTo(SubscriptionState.NONE);
        assertThat(result.actionType()).isEqualTo(StateTransitionEvent.ActionType.UNSUBSCRIBE);
    }

    @Test
    @DisplayName("동일 상태 멱등 요청 (BASIC → BASIC) → AlreadyInTargetStateException, csrng 호출 금지")
    void idempotentRequestRejected() {
        Channel channel = homepageChannel();
        Member existing = memberWithId(10L);
        Subscription existingSub = subscriptionWith(10L, channel.getId(), SubscriptionState.BASIC);
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.of(existingSub));

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.BASIC)))
                .isInstanceOf(com.artinus.membership.common.exception.AlreadyInTargetStateException.class);

        // 2-Phase 핵심 — Validator 단계에서 거부되면 csrng가 호출되지 않아야 한다.
        verify(csrngClient, never()).fetchRandomBit();
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    @DisplayName("csrng random=0 → ExternalValidationRejectedException, Applier 미진입")
    void csrngRejectsTransaction() {
        Channel channel = homepageChannel();
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(csrngClient.fetchRandomBit()).thenReturn(0);

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.BASIC)))
                .isInstanceOf(ExternalValidationRejectedException.class);

        // random=0이면 write 단계 미진입 — Member/Subscription save가 호출되지 않아야 한다.
        verify(memberRepository, never()).save(any(Member.class));
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(subscriptionHistoryRepository, never()).save(any(SubscriptionHistory.class));
    }

    @Test
    @DisplayName("csrng 인프라 장애 (CsrngException) → 그대로 전파, Applier 미진입")
    void csrngThrowsPropagates() {
        Channel channel = homepageChannel();
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(csrngClient.fetchRandomBit()).thenThrow(new CsrngException("upstream 5xx"));

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.BASIC)))
                .isInstanceOf(CsrngException.class);

        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    @DisplayName("낙관락 충돌 → ConcurrentModificationException으로 변환")
    void optimisticLockConflict() {
        Channel channel = homepageChannel();
        Member existing = memberWithId(10L);
        Subscription existingSub = subscriptionWith(10L, channel.getId(), SubscriptionState.BASIC);
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.of(existingSub));
        when(csrngClient.fetchRandomBit()).thenReturn(1);
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Subscription.class, 1L));

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.PREMIUM)))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    @DisplayName("UNIQUE(phone_number) 동시 INSERT 충돌 → ConcurrentModificationException")
    void uniquePhoneInsertConflict() {
        Channel channel = homepageChannel();
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(csrngClient.fetchRandomBit()).thenReturn(1);
        when(memberRepository.save(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for phone_number"));

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.BASIC)))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    @DisplayName("채널 정책 위반 (NAVER + UNSUBSCRIBE) → ChannelPolicyViolationException, csrng 미호출")
    void channelPolicyViolation() {
        // NAVER 채널은 unsubscribable=false
        Channel naver = Channel.builder()
                .id(3L).code("NAVER").name("네이버")
                .subscribable(true).unsubscribable(false)
                .createdAt(FIXED_NOW).build();
        Member existing = memberWithId(10L);
        Subscription existingSub = subscriptionWith(10L, naver.getId(), SubscriptionState.BASIC);
        when(channelRepository.findByCode("NAVER")).thenReturn(Optional.of(naver));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.of(existingSub));

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, "NAVER", SubscriptionState.NONE)))
                .isInstanceOf(ChannelPolicyViolationException.class);

        verify(csrngClient, never()).fetchRandomBit();
    }

    @Test
    @DisplayName("채널 미존재 → ResourceNotFoundException, csrng 미호출")
    void channelNotFound() {
        when(channelRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.execute(
                new SubscriptionRequest(PHONE, "UNKNOWN", SubscriptionState.BASIC)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(csrngClient, never()).fetchRandomBit();
    }

    @Test
    @DisplayName("Validator(read-only TX)와 Applier(write TX) 사이에 csrng가 호출되는지 호출 순서로 검증")
    void verifyTwoPhaseExecutionOrder() {
        Channel channel = homepageChannel();
        when(channelRepository.findByCode(CHANNEL_CODE)).thenReturn(Optional.of(channel));
        when(memberRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(memberRepository.save(any(Member.class))).thenReturn(memberWithId(10L));
        when(subscriptionRepository.findByMemberId(10L))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 200L));
        when(csrngClient.fetchRandomBit()).thenReturn(1);

        subscriptionService.execute(new SubscriptionRequest(PHONE, CHANNEL_CODE, SubscriptionState.PREMIUM));

        var inOrder = org.mockito.Mockito.inOrder(
                channelRepository, memberRepository, csrngClient, subscriptionRepository);
        // 1단계 read-only validate
        inOrder.verify(channelRepository).findByCode(CHANNEL_CODE);
        inOrder.verify(memberRepository).findByPhoneNumber(PHONE);
        // 2단계 csrng (TX 밖)
        inOrder.verify(csrngClient).fetchRandomBit();
        // 3단계 write apply
        inOrder.verify(memberRepository).save(any(Member.class));
        inOrder.verify(subscriptionRepository).save(any(Subscription.class));
        verify(subscriptionHistoryRepository, times(1)).save(any(SubscriptionHistory.class));
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private static Channel homepageChannel() {
        return Channel.builder()
                .id(1L).code(CHANNEL_CODE).name("홈페이지")
                .subscribable(true).unsubscribable(true)
                .createdAt(FIXED_NOW)
                .build();
    }

    private static Member memberWithId(long id) {
        return Member.builder()
                .id(id)
                .phoneNumber(PHONE)
                .version(0L)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .build();
    }

    private static Subscription subscriptionWith(long memberId, long channelId, SubscriptionState state) {
        return Subscription.builder()
                .id(200L)
                .memberId(memberId)
                .channelId(channelId)
                .state(state)
                .version(0L)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .subscribedAt(state == SubscriptionState.NONE ? null : FIXED_NOW)
                .build();
    }

    private static Subscription withId(Subscription s, long id) {
        // Builder의 id 슬롯을 통해 새 인스턴스 반환 (불변 패턴은 도메인이 권장 — 본 테스트에서는 같은 인스턴스를 흉내).
        return Subscription.builder()
                .id(id)
                .memberId(s.getMemberId())
                .channelId(s.getChannelId())
                .state(s.getState())
                .subscribedAt(s.getSubscribedAt())
                .canceledAt(s.getCanceledAt())
                .version(s.getVersion())
                .createdAt(s.getCreatedAt() == null ? FIXED_NOW : s.getCreatedAt())
                .updatedAt(s.getUpdatedAt() == null ? FIXED_NOW : s.getUpdatedAt())
                .build();
    }

}
