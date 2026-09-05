package io.privatekb.retrieval;

import io.privatekb.platform.LocalChatClient.ChatMessage;

import java.util.List;

record GroundedAnswerPlan(
        boolean refused,
        String refusalReason,
        List<GroundedCitation> citations,
        List<ChatMessage> messages
) {
    GroundedAnswerPlan {
        citations = List.copyOf(citations);
        messages = List.copyOf(messages);
    }

    static GroundedAnswerPlan refusal(String reason) {
        return new GroundedAnswerPlan(true, reason, List.of(), List.of());
    }
}
