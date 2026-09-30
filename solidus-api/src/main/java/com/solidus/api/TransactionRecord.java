package com.solidus.api;

import java.util.UUID;

/**
 * TransactionRecord — one entry of the append-only ledger, carried over
 * the contract boundary.
 *
 * <p>Contract mirror of the historic {@code TransactionLog.TransactionEntry}
 * record, with one deliberate normalization: {@code type} is a plain
 * {@code String} (the historic enum's {@code name()}) instead of a Core
 * enum, so the contract stays Minecraft- and Core-internal-free while
 * reflective 2.1.x-era readers keep finding the same component names.</p>
 *
 * <p>Well-known type codes (see Core's TransactionLog.Type): {@code PAY},
 * {@code SHOP_BUY}, {@code SHOP_SELL}, {@code AUCTION_LIST}, {@code AUCTION_BUY},
 * {@code AUCTION_PAYOUT}, {@code AUCTION_REFUND}, {@code ADMIN_SET},
 * {@code ADMIN_ADJUST}, and the migration/escrow bookkeeping codes.
 * Companions writing custom entries through
 * {@link SolidusApi#logTransaction} should prefix their codes with
 * {@code "COMPANION_"} to keep the namespace collision-free.</p>
 *
 * @param timestamp     epoch milliseconds of settlement
 * @param type          type code (see class doc)
 * @param playerUuid    acting player's UUID
 * @param playerName    acting player's name
 * @param targetUuid    counterparty's UUID (nullable)
 * @param targetName    counterparty's name (nullable)
 * @param amount        signed amount from the acting player's perspective
 * @param itemMaterial  item id for item-bound flows (nullable)
 * @param itemQuantity  item count for item-bound flows
 * @param description   human-readable note (nullable)
 * @since 2.3.0 (family contract 2.3)
 */
public record TransactionRecord(
    long timestamp,
    String type,
    UUID playerUuid,
    String playerName,
    UUID targetUuid,
    String targetName,
    double amount,
    String itemMaterial,
    int itemQuantity,
    String description
) {

    /**
     * Convenience factory stamping "now" as the timestamp.
     *
     * @param type    type code
     * @param player  acting player's UUID
     * @param name    acting player's name
     * @param amount  signed amount
     * @param description human-readable note
     * @return a ready record
     */
    public static TransactionRecord now(String type, UUID player, String name,
                                        double amount, String description) {
        return new TransactionRecord(System.currentTimeMillis(), type, player, name,
            null, null, amount, null, 0, description);
    }
}
