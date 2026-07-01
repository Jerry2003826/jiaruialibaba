package com.example.agentdemo.chat.memory;

import com.example.agentdemo.config.ConversationMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceUnitTest {

    @Test
    void loadRecentMessagesUsesPagedRecentQueryInsteadOfFullConversationScan() {
        ConversationMessageRepository repository = mock(ConversationMessageRepository.class);
        ConversationMemoryService service = new ConversationMemoryService(repository, properties(2));
        ConversationMessageEntity newest = new ConversationMessageEntity("conv-1", ConversationRole.ASSISTANT, "a2");
        ConversationMessageEntity previous = new ConversationMessageEntity("conv-1", ConversationRole.USER, "m2");
        when(repository.findByOwnerIdAndConversationIdOrderByCreatedAtDescIdDesc(eq("workbench-dev"), eq("conv-1"),
                        any(Pageable.class)))
                .thenReturn(List.of(newest, previous));

        List<ConversationMessage> messages = service.loadRecentMessages("conv-1");

        assertThat(messages).extracting(ConversationMessage::content).containsExactly("m2", "a2");
        verify(repository, never()).findByConversationIdOrderByCreatedAtAscIdAsc("conv-1");
    }

    @Test
    void trimOldMessagesDeletesOnlyPagedOverflowIds() {
        ConversationMessageRepository repository = mock(ConversationMessageRepository.class);
        ConversationMemoryService service = new ConversationMemoryService(repository, properties(2));
        when(repository.countByOwnerIdAndConversationId("workbench-dev", "conv-1")).thenReturn(4L);
        when(repository.findOldestIdsByOwnerIdAndConversationId(eq("workbench-dev"), eq("conv-1"),
                        any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));

        service.appendUserMessage("conv-1", "m3");

        verify(repository).save(any(ConversationMessageEntity.class));
        verify(repository).deleteByIdIn(List.of(1L, 2L));
        verify(repository, never()).findByConversationIdOrderByCreatedAtAscIdAsc("conv-1");
    }

    private ConversationMemoryProperties properties(int maxMessages) {
        ConversationMemoryProperties properties = new ConversationMemoryProperties();
        properties.setMaxMessages(maxMessages);
        return properties;
    }

}
