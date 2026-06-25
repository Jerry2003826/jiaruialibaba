package com.example.agentdemo.chat.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 128)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConversationRole role;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    protected ConversationMessageEntity() {
    }

    public ConversationMessageEntity(String conversationId, ConversationRole role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public ConversationRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
