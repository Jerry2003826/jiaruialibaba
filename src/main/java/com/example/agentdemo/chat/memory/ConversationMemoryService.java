package com.example.agentdemo.chat.memory;

import com.example.agentdemo.config.ConversationMemoryProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationMemoryService {

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
        List<ConversationMessageEntity> entities =
                conversationMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        int maxMessages = Math.max(1, properties.getMaxMessages());
        if (entities.size() <= maxMessages) {
            return entities.stream().map(this::toMessage).toList();
        }
        return entities.subList(entities.size() - maxMessages, entities.size())
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
        return conversationMessageRepository.deleteByConversationId(resolvedConversationId);
    }

    private void appendMessage(String conversationId, ConversationRole role, String content) {
        conversationMessageRepository.save(new ConversationMessageEntity(conversationId, role, content));
        trimOldMessages(conversationId);
    }

    private void trimOldMessages(String conversationId) {
        int maxMessages = Math.max(1, properties.getMaxMessages());
        List<ConversationMessageEntity> entities =
                conversationMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        if (entities.size() <= maxMessages) {
            return;
        }
        List<Long> idsToDelete = entities.subList(0, entities.size() - maxMessages).stream()
                .map(ConversationMessageEntity::getId)
                .toList();
        conversationMessageRepository.deleteByIdIn(idsToDelete);
    }

    private ConversationMessage toMessage(ConversationMessageEntity entity) {
        return new ConversationMessage(entity.getRole(), entity.getContent());
    }

}
