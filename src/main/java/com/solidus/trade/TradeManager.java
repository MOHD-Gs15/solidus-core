package com.solidus.trade;

import com.solidus.SolidusMod;
import com.solidus.chat.ChatPrompts;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.TransactionLog;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trade Manager - Orchestrates direct player-to-player trading (/trade).
 *
 * <p>Why this exists (from the 2.1.x community feedback): players were forced
 * to {@code /pay} then DROP items - the classic half-payment scam. A mutual
 * preview + mutual approval window eliminates the entire scam class for the
 * cost of one GUI flow, which is why every mainstream economy plugin ships
 * one (TradeMe, EconomyTrade, Essentials-style /trade ...).</p>
 *
 * <p>Safety model (the "usual simple things" done right):</p>
 * <ul>
 *   <li>Request/accept handshake with TTL + per-requester cooldown + distance
 *       check.</li>
 *   <li>Items escrow into the session containers the moment they are offered.</li>
 *   <li>Any offer change (items OR money) un-readies BOTH sides.</li>
 *   <li>Execution only when both are ready; money via the atomic
 *       {@code transferOffline} (hooks + limits apply); items swap with
 *       inventory-full drop fallback.</li>
 *   <li>Cancel on: close window, cancel button, disconnect, idle timeout,
 *       shutdown - every offered item returns to its owner. Nothing is ever
 *       dropped into the world by the trade system itself.</li>
 *   <li>Failed money leg = whole trade aborted with items returned; a
 *       partial money movement is rolled back (and logged if impossible).</li>
 * </ul>
 */
public class TradeManager {

    /** Max distance between the two traders (blocks). Standard proximity rule. */
    public static final double MAX_TRADE_DISTANCE = 10.0;

    /** Pending request TTL (ms). */
    public static final long REQUEST_TTL_MS = 30_000L;

    /** Cooldown between two requests from the same player (ms). */
    public static final long REQUEST_COOLDOWN_MS = 5_000L;

    /** Idle sessions older than this are reaped (items returned). */
    public static final long IDLE_SESSION_TTL_MS = 15 * 60 * 1000L;

    private final EconomyEngine economyEngine;
    private final ChatPrompts chatPrompts;

    /** Injected MinecraftServer - set via SERVER_STARTED (see SolidusMod). */
    private volatile MinecraftServer server;

    private record TradeRequest(UUID fromUuid, String fromName, long createdAt) {}
    /** target player -> pending incoming request */
    private final Map<UUID, TradeRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();

    /** player UUID -> the session they are part of (both sides mapped). */
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();

    public TradeManager(EconomyEngine economyEngine, ChatPrompts chatPrompts) {
        this.economyEngine = economyEngine;
        this.chatPrompts = chatPrompts;
    }

    /** Injects the MinecraftServer instance (SolidusMod, SERVER_STARTED). */
    public void setServer(MinecraftServer server) {
        this.server = server;
        SolidusMod.LOGGER.info("TradeManager: MinecraftServer instance injected.");
    }

    // -- Request handshake ---------------------------------

    /** {@code /trade <player>} - request a trade with an online player. */
    public void requestTrade(ServerPlayer requester, ServerPlayer target) {
        if (requester.getUUID().equals(target.getUUID())) {
            requester.sendSystemMessage(TextUtil.error("You cannot trade with yourself."));
            return;
        }
        if (getSession(requester.getUUID()) != null) {
            requester.sendSystemMessage(TextUtil.error("You are already in a trade."));
            return;
        }
        if (getSession(target.getUUID()) != null) {
            requester.sendSystemMessage(TextUtil.error(
                target.getName().getString() + " is already in a trade."));
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastRequestAt.get(requester.getUUID());
        if (last != null && now - last < REQUEST_COOLDOWN_MS) {
            long wait = (REQUEST_COOLDOWN_MS - (now - last) + 999) / 1000;
            requester.sendSystemMessage(TextUtil.error(
                "Please wait " + wait + "s before requesting another trade."));
            return;
        }
        lastRequestAt.put(requester.getUUID(), now);

        // Proximity rule (standard): both must stand near each other.
        if (requester.level() != target.level()
                || requester.distanceTo(target) > MAX_TRADE_DISTANCE) {
            requester.sendSystemMessage(TextUtil.error(
                "You must be within " + (int) MAX_TRADE_DISTANCE + " blocks of " +
                    target.getName().getString() + " to trade."));
            return;
        }

        pendingRequests.put(target.getUUID(),
            new TradeRequest(requester.getUUID(), requester.getName().getString(), now));

        requester.sendSystemMessage(TextUtil.success(
            "Trade request sent to " + target.getName().getString()
                + ". They have 30 seconds to accept."));
        target.sendSystemMessage(TextUtil.styled(
            requester.getName().getString() + " wants to trade with you. Type ", ChatFormatting.GOLD)
            .append(TextUtil.styled("/trade accept", ChatFormatting.GREEN))
            .append(TextUtil.styled(" to open the trade window, or ", ChatFormatting.GOLD))
            .append(TextUtil.styled("/trade deny", ChatFormatting.RED))
            .append(TextUtil.styled(" to refuse.", ChatFormatting.GOLD)));
    }

    /** {@code /trade accept} - accept a pending request targeting me. */
    public void acceptTrade(ServerPlayer player) {
        TradeRequest request = pendingRequests.remove(player.getUUID());
        if (request == null || System.currentTimeMillis() - request.createdAt() > REQUEST_TTL_MS) {
            player.sendSystemMessage(TextUtil.error("You have no pending trade request."));
            return;
        }
        ServerPlayer initiator = server.getPlayerList().getPlayer(request.fromUuid());
        if (initiator == null) {
            player.sendSystemMessage(TextUtil.error("That player went offline."));
            return;
        }
        if (getSession(initiator.getUUID()) != null || getSession(player.getUUID()) != null) {
            player.sendSystemMessage(TextUtil.error("One of you is already in a trade."));
            return;
        }

        // Open the session.
        TradeSession session = new TradeSession(UUID.randomUUID(),
            initiator.getUUID(), initiator.getName().getString(),
            player.getUUID(), player.getName().getString());
        sessions.put(initiator.getUUID(), session);
        sessions.put(player.getUUID(), session);

        initiator.sendSystemMessage(TextUtil.success(
            player.getName().getString() + " accepted - trade window opened."));
        TradeScreenHandler.openFor(initiator, this, session, TradeSession.Side.INITIATOR);
        TradeScreenHandler.openFor(player, this, session, TradeSession.Side.PARTNER);
        SolidusMod.LOGGER.info("Trade session {} opened: {} <-> {}",
            session.sessionId(), session.nameOf(TradeSession.Side.INITIATOR),
            session.nameOf(TradeSession.Side.PARTNER));
    }

    /** {@code /trade deny} - refuse a pending request. */
    public void denyTrade(ServerPlayer player) {
        TradeRequest request = pendingRequests.remove(player.getUUID());
        if (request == null) {
            player.sendSystemMessage(TextUtil.error("You have no pending trade request."));
            return;
        }
        player.sendSystemMessage(TextUtil.styled("Trade request declined.", ChatFormatting.GRAY));
        ServerPlayer requester = server.getPlayerList().getPlayer(request.fromUuid());
        if (requester != null) {
            requester.sendSystemMessage(TextUtil.warning(
                player.getName().getString() + " declined your trade request."));
        }
    }

    /** {@code /trade cancel} - cancel my current session from the command line. */
    public void cancelMyTrade(ServerPlayer player) {
        TradeSession session = sessions.get(player.getUUID());
        if (session == null) {
            player.sendSystemMessage(TextUtil.error("You are not in a trade."));
            return;
        }
        cancelSession(session, "used /trade cancel", player.getName().getString());
    }

    // -- Session lifecycle ---------------------------------

    /** Toggle MY ready flag; executes when both sides end up ready. */
    public void toggleReady(TradeSession session, TradeSession.Side side) {
        if (session.state() != TradeSession.State.ACTIVE) return;
        boolean newReady = !session.isReady(side);
        session.setReady(side, newReady);

        ServerPlayer me = resolvePlayer(session.uuidOf(side));
        if (me != null) {
            if (newReady) {
                me.sendSystemMessage(TextUtil.success("You are ready. Waiting for partner..."));
            } else {
                me.sendSystemMessage(TextUtil.warning("Ready state withdrawn."));
            }
        }
        refreshPartnerView(session, side);

        if (session.bothReady()) {
            executeTrade(session);
        }
    }

    /** Sets my money offer (chat prompt result). amount <= 0 clears it. */
    public void setMoneyOffer(TradeSession session, TradeSession.Side side, double amount) {
        if (session.state() != TradeSession.State.ACTIVE) return;

        if (amount < 0 || !Double.isFinite(amount) || amount > CurrencyUtil.MAX_TRANSACTION) {
            ServerPlayer me = resolvePlayer(session.uuidOf(side));
            if (me != null) {
                me.sendSystemMessage(TextUtil.error(
                    "Money offer must be between 0 and " + CurrencyUtil.format(CurrencyUtil.MAX_TRANSACTION) + "."));
            }
            return;
        }

        double rounded = CurrencyUtil.round(amount);
        session.setMoney(side, rounded);

        ServerPlayer me = resolvePlayer(session.uuidOf(side));
        if (me != null) {
            if (rounded > 0) {
                me.sendSystemMessage(TextUtil.success(
                    "Money offer set to " + CurrencyUtil.format(rounded) + "."));
            } else {
                me.sendSystemMessage(TextUtil.styled("Money offer cleared.", ChatFormatting.GRAY));
            }
            // Refresh the offerer's OWN window too - the prompt can fire while
            // their trade GUI is still open (chat works over any GUI).
            if (me.containerMenu instanceof TradeScreenHandler ownHandler
                    && ownHandler.session() == session) {
                ownHandler.refreshStatusDisplays();
                ownHandler.refreshMirrorFromPartner();
                me.containerMenu.broadcastFullState();
            }
        }
        refreshPartnerView(session, side);
    }

    /**
     * Copies MY current offer into the PARTNER's container mirror slots
     * (their right-hand display columns).
     */
    public void mirrorOffer(TradeSession session, TradeSession.Side side) {
        TradeContainer myContainer = session.containerOf(side);
        TradeContainer otherContainer = session.containerOf(TradeSession.other(side));
        for (int i = 0; i < TradeGUI.MY_OFFER_SLOTS.length; i++) {
            otherContainer.setItem(TradeGUI.THEIR_OFFER_SLOTS[i],
                myContainer.getItem(TradeGUI.MY_OFFER_SLOTS[i]).copy());
        }
    }

    /**
     * Refreshes the PARTNER's view after I changed something: re-mirror,
     * status items, and a full resync of their open menu.
     */
    public void refreshPartnerView(TradeSession session, TradeSession.Side side) {
        ServerPlayer other = resolvePlayer(session.uuidOf(TradeSession.other(side)));
        if (other == null) return;
        if (other.containerMenu instanceof TradeScreenHandler partnerHandler
                && partnerHandler.session() == session) {
            partnerHandler.refreshMirrorFromPartner();
            partnerHandler.refreshStatusDisplays();
            other.containerMenu.broadcastFullState();
        }
    }

    /**
     * Cancels a session: every offered item returns to its owner, both
     * windows close, both players are notified. Idempotent.
     *
     * @param reasonPhrase short human reason ("closed the trade window", ...)
     * @param actorName    who caused the cancellation (for the messages)
     */
    public void cancelSession(TradeSession session, String reasonPhrase, String actorName) {
        if (session.isTerminal()) return;
        if (session.state() == TradeSession.State.EXECUTING) {
            // Execution is in flight - items/money are being settled; do not
            // double-handle. (Execution never calls back into cancel.)
            return;
        }
        session.markCancelled();

        // Return ALL offered items to their (resolved) owners.
        for (TradeSession.Side side : new TradeSession.Side[]{
                TradeSession.Side.INITIATOR, TradeSession.Side.PARTNER}) {
            List<ItemStack> items = session.claimOfferedItems(side);
            ServerPlayer owner = resolvePlayer(session.uuidOf(side));
            if (owner != null && owner.isAlive()) {
                for (ItemStack stack : items) {
                    if (!owner.getInventory().add(stack)) {
                        owner.drop(stack, false);
                    }
                }
            }
            // Owner offline: cannot happen (disconnect cancels synchronously
            // while the player is still resolvable) - the claim above already
            // removed the items from the container.
        }

        sessions.remove(session.uuidOf(TradeSession.Side.INITIATOR));
        sessions.remove(session.uuidOf(TradeSession.Side.PARTNER));

        closeWindow(session.uuidOf(TradeSession.Side.INITIATOR));
        closeWindow(session.uuidOf(TradeSession.Side.PARTNER));

        notifySide(session, TradeSession.Side.INITIATOR, TextUtil.warning(
            "Trade cancelled (" + actorName + " " + reasonPhrase + "). Your offered items were returned."));
        notifySide(session, TradeSession.Side.PARTNER, TextUtil.warning(
            "Trade cancelled (" + actorName + " " + reasonPhrase + "). Your offered items were returned."));

        SolidusMod.LOGGER.info("Trade session {} cancelled by {} ({})",
            session.sessionId(), actorName, reasonPhrase);
    }

    /** Disconnect handling: cancel any session + pending request state. */
    public void handleDisconnect(ServerPlayer player) {
        pendingRequests.remove(player.getUUID());
        lastRequestAt.remove(player.getUUID());
        TradeSession session = sessions.get(player.getUUID());
        if (session != null) {
            cancelSession(session, "disconnected", player.getName().getString());
        }
        if (chatPrompts != null) {
            chatPrompts.cancelPrompt(player.getUUID());
        }
    }

    /** Reaps idle sessions (called periodically from the server tick). */
    public void reapIdleSessions() {
        long now = System.currentTimeMillis();
        for (TradeSession session : new ArrayList<>(sessions.values())) {
            if (session.state() == TradeSession.State.ACTIVE
                    && now - session.lastActivity() > IDLE_SESSION_TTL_MS) {
                cancelSession(session, "timed out after " + (IDLE_SESSION_TTL_MS / 60000)
                    + " minutes of inactivity", "it");
            }
        }
        // Expire stale requests.
        pendingRequests.entrySet().removeIf(e ->
            now - e.getValue().createdAt() > REQUEST_TTL_MS);
    }

    /** Server shutdown: return everything, silently. */
    public void shutdown() {
        for (TradeSession session : new ArrayList<>(sessions.values())) {
            if (!session.isTerminal()) {
                session.markCancelled();
                // On shutdown players are being saved; resolve may already be
                // dead - items are claimed out of the containers regardless.
                for (TradeSession.Side side : new TradeSession.Side[]{
                        TradeSession.Side.INITIATOR, TradeSession.Side.PARTNER}) {
                    List<ItemStack> items = session.claimOfferedItems(side);
                    ServerPlayer owner = resolvePlayer(session.uuidOf(side));
                    if (owner != null) {
                        for (ItemStack stack : items) {
                            if (!owner.getInventory().add(stack)) {
                                owner.drop(stack, false);
                            }
                        }
                    }
                }
            }
        }
        sessions.clear();
        pendingRequests.clear();
    }

    // -- Execution -----------------------------------------

    /**
     * Both sides ready: move the money atomically, then swap the items.
     * Runs on the server thread; money legs chain on the economy executor.
     */
    private void executeTrade(TradeSession session) {
        if (session.state() != TradeSession.State.ACTIVE) return;

        ServerPlayer a = resolvePlayer(session.uuidOf(TradeSession.Side.INITIATOR));
        ServerPlayer b = resolvePlayer(session.uuidOf(TradeSession.Side.PARTNER));
        if (a == null || b == null) {
            // Someone vanished between READY and execute - cancel handles it.
            cancelSession(session, "went offline", "A player");
            return;
        }

        if (session.isEmptyTrade()) {
            session.setReady(TradeSession.Side.INITIATOR, false);
            session.setReady(TradeSession.Side.PARTNER, false);
            a.sendSystemMessage(TextUtil.error("Trade is empty - put items or money up first."));
            b.sendSystemMessage(TextUtil.error("Trade is empty - put items or money up first."));
            refreshPartnerView(session, TradeSession.Side.INITIATOR);
            refreshPartnerView(session, TradeSession.Side.PARTNER);
            return;
        }

        session.markExecuting();

        double moneyA = session.moneyOf(TradeSession.Side.INITIATOR);
        double moneyB = session.moneyOf(TradeSession.Side.PARTNER);
        BalanceManager balances = economyEngine.getBalanceManager();

        // Money phase (both legs atomic, sequential, with rollback of leg 1
        // if leg 2 fails - item swap only starts when ALL money is settled).
        executeMoneyLegs(session, balances, moneyA, moneyB)
            .thenAccept(moneyResult -> a.level().getServer().execute(() -> {
                if (session.isTerminal()) {
                    // A cancel raced us (should not happen while EXECUTING,
                    // but stay defensive) - nothing to do.
                    return;
                }
                if (!moneyResult.success()) {
                    // Money failed or rolled back - abort the whole trade.
                    session.markCancelled();
                    returnItemsToOwners(session);
                    sessions.remove(a.getUUID());
                    sessions.remove(b.getUUID());
                    closeWindow(a.getUUID());
                    closeWindow(b.getUUID());
                    a.sendSystemMessage(TextUtil.error("Trade failed: " + moneyResult.message()));
                    b.sendSystemMessage(TextUtil.error("Trade failed: " + moneyResult.message()
                        + " - your offered items were returned."));
                    SolidusMod.LOGGER.warn("Trade session {} aborted during money phase: {}",
                        session.sessionId(), moneyResult.message());
                    return;
                }

                // Items phase (money is fully settled at this point).
                List<ItemStack> aToB = session.takeOfferedItems(TradeSession.Side.INITIATOR);
                List<ItemStack> bToA = session.takeOfferedItems(TradeSession.Side.PARTNER);

                for (ItemStack stack : aToB) {
                    if (!b.getInventory().add(stack)) b.drop(stack, false);
                }
                for (ItemStack stack : bToA) {
                    if (!a.getInventory().add(stack)) a.drop(stack, false);
                }

                session.markCompleted();
                sessions.remove(a.getUUID());
                sessions.remove(b.getUUID());

                // Ledger: one TRADE_SEND + one TRADE_RECEIVE per direction
                // with content summary (per-item rows would flood the ledger;
                // /transactions shows the full item breakdown in the notes).
                logTradeLeg(a, b, aToB, moneyA);
                logTradeLeg(b, a, bToA, moneyB);

                closeWindow(a.getUUID());
                closeWindow(b.getUUID());

                a.sendSystemMessage(TextUtil.success("Trade completed!"));
                b.sendSystemMessage(TextUtil.success("Trade completed!"));
                SolidusMod.LOGGER.info("Trade session {} completed: {} <-> {} (items {}<->{}, money {}<->{})",
                    session.sessionId(),
                    a.getName().getString(), b.getName().getString(),
                    aToB.size(), bToA.size(),
                    CurrencyUtil.format(moneyA), CurrencyUtil.format(moneyB));
            }));
    }

    private record MoneyPhaseResult(boolean success, String message) {}

    /**
     * Runs the two money legs sequentially on the economy executor with a
     * rollback of the first leg if the second fails. A rolled-back or
     * failed phase moves NO money at all.
     */
    private java.util.concurrent.CompletableFuture<MoneyPhaseResult> executeMoneyLegs(
            TradeSession session, BalanceManager balances, double moneyA, double moneyB) {
        UUID aUuid = session.uuidOf(TradeSession.Side.INITIATOR);
        String aName = session.nameOf(TradeSession.Side.INITIATOR);
        UUID bUuid = session.uuidOf(TradeSession.Side.PARTNER);
        String bName = session.nameOf(TradeSession.Side.PARTNER);

        if (moneyA <= 0 && moneyB <= 0) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                new MoneyPhaseResult(true, ""));
        }

        return balances.transferOffline(aUuid, aName, bUuid, bName, moneyA)
            .thenCompose(first -> {
                if (moneyA > 0 && !first.success()) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        new MoneyPhaseResult(false, first.message()));
                }
                if (moneyB <= 0) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        new MoneyPhaseResult(true, ""));
                }
                return balances.transferOffline(bUuid, bName, aUuid, aName, moneyB)
                    .thenCompose(second -> {
                        if (!second.success()) {
                            // Leg 2 failed after leg 1 committed -> roll leg 1 back.
                            if (moneyA > 0) {
                                return balances.transferOffline(bUuid, bName, aUuid, aName, moneyA)
                                    .thenApply(rollback -> rollback.success()
                                        ? new MoneyPhaseResult(false, second.message())
                                        : new MoneyPhaseResult(false,
                                            "CRITICAL: money rollback failed - contact an admin (session "
                                                + session.sessionId() + ")"));
                            }
                        }
                        return java.util.concurrent.CompletableFuture.completedFuture(
                            new MoneyPhaseResult(true, ""));
                    });
            });
    }

    /** Logs one completed trade direction into the transaction ledger. */
    private void logTradeLeg(ServerPlayer from, ServerPlayer to,
                              List<ItemStack> items, double money) {
        TransactionLog log = economyEngine.getTransactionLog();
        String summary = items.isEmpty()
            ? "no items"
            : items.size() + " item stack(s): " + items.stream()
                .map(s -> s.getCount() + "x " + TextUtil.getMaterialName(s))
                .reduce((x, y) -> x + ", " + y).orElse("");
        log.log(
            TransactionLog.Type.TRADE_SEND,
            from.getUUID(), from.getName().getString(),
            to.getUUID(), to.getName().getString(),
            money,
            items.isEmpty() ? null : TextUtil.getMaterialName(items.get(0)),
            items.stream().mapToInt(ItemStack::getCount).sum(),
            "Traded with " + to.getName().getString() + " - gave " + summary
                + (money > 0 ? " + " + CurrencyUtil.format(money) : ""));
        log.log(
            TransactionLog.Type.TRADE_RECEIVE,
            to.getUUID(), to.getName().getString(),
            from.getUUID(), from.getName().getString(),
            money,
            items.isEmpty() ? null : TextUtil.getMaterialName(items.get(0)),
            items.stream().mapToInt(ItemStack::getCount).sum(),
            "Traded with " + from.getName().getString() + " - received " + summary
                + (money > 0 ? " + " + CurrencyUtil.format(money) : ""));
    }

    // -- Helpers -------------------------------------------

    public TradeSession getSession(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    private ServerPlayer resolvePlayer(UUID playerUuid) {
        MinecraftServer currentServer = this.server;
        return currentServer != null
            ? currentServer.getPlayerList().getPlayer(playerUuid) : null;
    }

    private void returnItemsToOwners(TradeSession session) {
        for (TradeSession.Side side : new TradeSession.Side[]{
                TradeSession.Side.INITIATOR, TradeSession.Side.PARTNER}) {
            List<ItemStack> items = session.claimOfferedItems(side);
            ServerPlayer owner = resolvePlayer(session.uuidOf(side));
            if (owner != null && owner.isAlive()) {
                for (ItemStack stack : items) {
                    if (!owner.getInventory().add(stack)) {
                        owner.drop(stack, false);
                    }
                }
            }
        }
    }

    private void notifySide(TradeSession session, TradeSession.Side side,
                             net.minecraft.network.chat.Component message) {
        ServerPlayer player = resolvePlayer(session.uuidOf(side));
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private void closeWindow(UUID playerUuid) {
        ServerPlayer player = resolvePlayer(playerUuid);
        if (player == null) return;
        try {
            if (player.containerMenu instanceof TradeScreenHandler) {
                player.closeContainer();
            }
        } catch (Exception e) {
            SolidusMod.LOGGER.warn("Could not close trade window for {}",
                player.getName().getString(), e);
        }
    }
}
