package com.artinus.membership.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 채널은 V1__init.sql에 6개 시드되며 code로 조회. */
public interface ChannelRepository extends JpaRepository<Channel, Long> {

    Optional<Channel> findByCode(String code);
}
