package com.example.agentdemo.chat.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({ConversationMemoryService.class, com.example.agentdemo.config.ConversationMemoryProperties.class})
@TestPropertySource(properties = "demo.chat.memory.max-messages=3")
class ConversationMemoryServiceTest {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Test
    void generatesConversationIdWhenMissing() {
        String conversationId = conversationMemoryService.resolveConversationId(null);
        assertThat(conversationId).isNotBlank();
    }

    @Test
    void keepsOnlyRecentMessagesWithinLimit() {
        String conversationId = "conv-1";
        conversationMemoryService.appendUserMessage(conversationId, "m1");
        conversationMemoryService.appendAssistantMessage(conversationId, "a1");
        conversationMemoryService.appendUserMessage(conversationId, "m2");
        conversationMemoryService.appendAssistantMessage(conversationId, "a2");

        assertThat(conversationMemoryService.loadRecentMessages(conversationId))
                .extracting(ConversationMessage::content)
                .containsExactly("a1", "m2", "a2");
    }

    @Test
    void trimsPersistedMessagesBeyondLimit() {
        String conversationId = "conv-trim";
        conversationMemoryService.appendUserMessage(conversationId, "m1");
        conversationMemoryService.appendAssistantMessage(conversationId, "a1");
        conversationMemoryService.appendUserMessage(conversationId, "m2");
        conversationMemoryService.appendAssistantMessage(conversationId, "a2");
        conversationMemoryService.appendUserMessage(conversationId, "m3");

        assertThat(conversationMemoryService.loadRecentMessages(conversationId))
                .extracting(ConversationMessage::content)
                .containsExactly("m2", "a2", "m3");
    }

}
