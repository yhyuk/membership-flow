package com.artinus.membership.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** phone_number UNIQUE 제약으로 동시 가입 경합 감지. */
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhoneNumber(String phoneNumber);
}
