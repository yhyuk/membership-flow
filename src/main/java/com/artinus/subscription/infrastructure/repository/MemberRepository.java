package com.artinus.subscription.infrastructure.repository;

import com.artinus.subscription.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 회원 영속 어댑터.
 *
 * <p>Spring Data JPA {@link JpaRepository} 인터페이스만 사용한다.
 * 커스텀 구현(Custom Impl)/QueryDSL은 도입하지 않는다 (오버엔지니어링 회피).</p>
 *
 * <p>{@code phoneNumber} UNIQUE 제약(V1__init.sql)에 의존해 동시 가입 경합을 감지한다(handoff M-4).</p>
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /** 정규화된 휴대폰 번호로 회원 조회. UNIQUE 제약 활용. */
    Optional<Member> findByPhoneNumber(String phoneNumber);
}
