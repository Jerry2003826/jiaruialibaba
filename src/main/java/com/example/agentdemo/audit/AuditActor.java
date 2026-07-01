package com.example.agentdemo.audit;

/**
 * Immutable snapshot of who is performing an action and from where, resolved from the current
 * security context and HTTP request.
 *
 * @param ownerId   owner the action is scoped to (for console isolation)
 * @param type      the actor category
 * @param actorId   stable identifier of the actor (JWT subject or API key id)
 * @param ip        best-effort client IP (X-Forwarded-For first hop or remote address)
 * @param userAgent client user agent (may be {@code null})
 */
public record AuditActor(String ownerId, AuditActorType type, String actorId, String ip, String userAgent) {
}
