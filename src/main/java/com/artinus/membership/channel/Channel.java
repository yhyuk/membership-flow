package com.artinus.membership.channel;

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
