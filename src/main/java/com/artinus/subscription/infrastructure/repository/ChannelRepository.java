package com.artinus.subscription.infrastructure.repository;

import com.artinus.subscription.domain.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 채널 영속 어댑터.
 *
 * <p>채널은 시드 데이터로 부팅 시점에 6개가 적재된다(V1__init.sql).
 * 외부 입력은 {@code code} 문자열이므로 코드 기반 조회만 노출한다.</p>
 */
public interface ChannelRepository extends JpaRepository<Channel, Long> {

    /** 채널 식별 코드로 조회. (예: {@code HOMEPAGE}, {@code MOBILE_APP}, {@code CALL_CENTER}) */
    Optional<Channel> findByCode(String code);
}
