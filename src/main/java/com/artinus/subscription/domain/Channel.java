package com.artinus.subscription.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 구독/해지 채널 엔티티.
 *
 * <p>{@code subscribable}/{@code unsubscribable} 플래그로 채널 타입을 표현한다
 * (ASSIGNMENT line 26-30). 시드 데이터는 Flyway V1__init.sql에서 INSERT.</p>
 */
@Entity
@Table(name = "channels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "subscribable", nullable = false)
    private boolean subscribable;

    @Column(name = "unsubscribable", nullable = false)
    private boolean unsubscribable;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Channel(Long id, String code, String name, boolean subscribable, boolean unsubscribable,
                    LocalDateTime createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.subscribable = subscribable;
        this.unsubscribable = unsubscribable;
        this.createdAt = createdAt;
    }
}
