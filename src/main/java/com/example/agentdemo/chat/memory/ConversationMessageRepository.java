package com.example.agentdemo.chat.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, Long> {

    List<ConversationMessageEntity> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);

    List<ConversationMessageEntity> findByOwnerIdAndConversationIdOrderByCreatedAtAscIdAsc(String ownerId,
            String conversationId);

    List<ConversationMessageEntity> findByConversationIdOrderByCreatedAtDescIdDesc(String conversationId,
            Pageable pageable);

    List<ConversationMessageEntity> findByOwnerIdAndConversationIdOrderByCreatedAtDescIdDesc(String ownerId,
            String conversationId, Pageable pageable);

    @Query("""
            select message.id
            from ConversationMessageEntity message
            where message.ownerId = :ownerId
              and message.conversationId = :conversationId
            order by message.createdAt asc, message.id asc
            """)
    List<Long> findOldestIdsByOwnerIdAndConversationId(@Param("ownerId") String ownerId,
            @Param("conversationId") String conversationId, Pageable pageable);

    long countByConversationId(String conversationId);

    long countByOwnerIdAndConversationId(String ownerId, String conversationId);

    long deleteByConversationId(String conversationId);

    long deleteByOwnerIdAndConversationId(String ownerId, String conversationId);

    void deleteByIdIn(Collection<Long> ids);

}
