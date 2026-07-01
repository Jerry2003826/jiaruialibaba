package com.example.agentdemo.chat.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts persisted conversation history plus the current user message into the Spring AI
 * {@link Message} list expected by {@code ChatClient}. Shared so the chat and agent services do not
 * keep divergent copies of the same mapping.
 */
public final class SpringMessageConverter {

    private SpringMessageConverter() {
    }

    /**
     * Build the ordered Spring AI message list: history (user/assistant) followed by the new user
     * message. A {@code null} history is treated as empty.
     *
     * @param history     prior conversation turns, may be {@code null}
     * @param userMessage the current user message to append
     * @return the ordered Spring AI messages
     */
    public static List<Message> toSpringMessages(List<ConversationMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        if (history != null) {
            for (ConversationMessage message : history) {
                if (message.role() == ConversationRole.USER) {
                    messages.add(new UserMessage(message.content()));
                }
                else {
                    messages.add(new AssistantMessage(message.content()));
                }
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }
}
