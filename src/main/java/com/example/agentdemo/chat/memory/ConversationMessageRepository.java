package com.example.agentdemo.chat.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, Long> {

    List<ConversationMessageEntity> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);

    long countByConversationId(String conversationId);

    long deleteByConversationId(String conversationId);

    void deleteByIdIn(Collection<Long> ids);

}
