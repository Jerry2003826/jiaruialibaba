package com.example.agentdemo.chat.memory;

import com.example.agentdemo.config.ConversationMemoryProperties;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationMemoryService {

    private static final int TRIM_BATCH_SIZE = 1_000;

    private final ConversationMessageRepository conversationMessageRepository;
    private final ConversationMemoryProperties properties;

    public ConversationMemoryService(ConversationMessageRepository conversationMessageRepository,
            ConversationMemoryProperties properties) {
        this.conversationMessageRepository = conversationMessageRepository;
        this.properties = properties;
    }

    /**
     * Resolve a stable conversation id, generating one when absent.
     */
    public String resolveConversationId(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            return conversationId.trim();
        }
        return UUID.randomUUID().toString();
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> loadRecentMessages(String conversationId) {
        int maxMessages = Math.max(1, properties.getMaxMessages());
        String ownerId = SecurityIdentity.currentOwnerId();
        List<ConversationMessageEntity> entities = new ArrayList<>(
                conversationMessageRepository.findByOwnerIdAndConversationIdOrderByCreatedAtDescIdDesc(ownerId,
                        conversationId, PageRequest.of(0, maxMessages)));
        Collections.reverse(entities);
        return entities
                .stream()
                .map(this::toMessage)
                .toList();
    }

    @Transactional
    public void appendUserMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.USER, content);
    }

    @Transactional
    public void appendAssistantMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.ASSISTANT, content);
    }

    @Transactional
    public long clearConversation(String conversationId) {
        String resolvedConversationId = resolveConversationId(conversationId);
        return conversationMessageRepository.deleteByOwnerIdAndConversationId(SecurityIdentity.currentOwnerId(),
                resolvedConversationId);
    }

    private void appendMessage(String conversationId, ConversationRole role, String content) {
        conversationMessageRepository.save(new ConversationMessageEntity(conversationId, role, content));
        trimOldMessages(conversationId);
    }

    private void trimOldMessages(String conversationId) {
        int maxMessages = Math.max(1, properties.getMaxMessages());
        String ownerId = SecurityIdentity.currentOwnerId();
        long messageCount = conversationMessageRepository.countByOwnerIdAndConversationId(ownerId, conversationId);
        long excess = messageCount - maxMessages;
        if (excess <= 0) {
            return;
        }
        while (excess > 0) {
            int batchSize = (int) Math.min(excess, TRIM_BATCH_SIZE);
            List<Long> idsToDelete = conversationMessageRepository.findOldestIdsByOwnerIdAndConversationId(ownerId,
                    conversationId, PageRequest.of(0, batchSize));
            if (idsToDelete.isEmpty()) {
                return;
            }
            conversationMessageRepository.deleteByIdIn(idsToDelete);
            excess -= idsToDelete.size();
        }
    }

    private ConversationMessage toMessage(ConversationMessageEntity entity) {
        return new ConversationMessage(entity.getRole(), entity.getContent());
    }

}
