package com.solidus.chat;

import com.solidus.SolidusMod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat Prompt Service - "type the amount in chat" input flow.
 *
 * <p>This is the standard UX used by mainstream auction/trade plugins
 * (AuctionHouse, TradeMe, zAuctionHouse): a GUI button that needs a numeric
 * amount closes into a chat prompt ("Type the amount in chat, or 'cancel'"),
 * and the player's NEXT chat message is consumed as the input instead of
 * being broadcast. This avoids inventing any custom GUI widget for text
 * input, which vanilla clients cannot do without client-side mods.</p>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>Uses {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} so a consumed
 *       message returns {@code false} - the message is NOT broadcast to
 *       other players (privacy of the typed amount).</li>
 *   <li>Prompts expire after {@link #PROMPT_TTL_MS} (5 minutes) or when the
 *       player disconnects / a new prompt replaces the old one.</li>
 *   <li>Handlers run on the server thread (the event's thread).</li>
 *   <li>The service is fail-safe: any handler exception is caught and
 *       logged, and the prompt is cleared.</li>
 * </ul>
 */
public final class ChatPrompts {

    /** Handler result: consume the message (suppress broadcast). */
    public static final boolean CONSUME = true;
    /** Handler result: let the chat through normally. */
    public static final boolean PASS_THROUGH = false;

    /** How long a prompt stays valid after being opened. */
    public static final long PROMPT_TTL_MS = 5 * 60 * 1000L;

    @FunctionalInterface
    public interface PromptHandler {
        /**
         * @param player  the chatting player
         * @param message the raw chat text
         * @return {@code true} to CONSUME the message (not broadcast),
         *         {@code false} to pass it through and clear the prompt
         */
        boolean onChat(ServerPlayer player, String message);
    }

    private record PendingPrompt(PromptHandler handler, long createdAt) {}

    private final Map<UUID, PendingPrompt> prompts = new ConcurrentHashMap<>();

    public ChatPrompts() {
        // Consume the next chat message when a prompt is open.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            PendingPrompt prompt = prompts.get(sender.getUUID());
            if (prompt == null) return true; // no prompt - normal chat

            prompts.remove(sender.getUUID()); // single-shot: always clear

            // TTL guard - stale prompts fall through to normal chat.
            if (System.currentTimeMillis() - prompt.createdAt() > PROMPT_TTL_MS) {
                return true;
            }

            try {
                return prompt.handler().onChat(sender, message.signedContent());
            } catch (Exception e) {
                SolidusMod.LOGGER.error("Chat prompt handler failed for {}",
                    sender.getName().getString(), e);
                return true;
            }
        });

        // Expire stale prompts lazily on tick (cheap sweep).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            if ((now / 2000L) % 2L != 0L) return; // roughly every 2-4 seconds
            prompts.entrySet().removeIf(e -> now - e.getValue().createdAt() > PROMPT_TTL_MS);
        });
    }

    /**
     * Opens a chat prompt for the player: their next chat message is routed
     * to the handler instead of being broadcast.
     *
     * @param player  the player to prompt
     * @param handler the single-shot handler
     */
    public void openPrompt(ServerPlayer player, PromptHandler handler) {
        prompts.put(player.getUUID(), new PendingPrompt(handler, System.currentTimeMillis()));
    }

    /**
     * Cancels any open prompt for the player (e.g. on disconnect or when a
     * related GUI flow is aborted).
     */
    public void cancelPrompt(UUID playerUuid) {
        prompts.remove(playerUuid);
    }
}
