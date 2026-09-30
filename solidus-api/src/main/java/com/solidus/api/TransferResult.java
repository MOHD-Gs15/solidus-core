package com.solidus.api;

/**
 * TransferResult — settlement outcome of an atomic peer-to-peer move.
 *
 * <p>Contract mirror of the historic {@code BalanceManager.TransferResult}
 * record; component names are part of the stable surface.</p>
 *
 * @param success            true when both sides settled atomically
 * @param message            human-readable outcome (player-facing on failure)
 * @param senderNewBalance    sender's balance after settlement (undefined on failure)
 * @param receiverNewBalance  receiver's balance after settlement (undefined on failure)
 * @since 2.3.0 (family contract 2.3)
 */
public record TransferResult(
    boolean success,
    String message,
    double senderNewBalance,
    double receiverNewBalance
) {
}
