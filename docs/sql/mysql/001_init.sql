-- ============================================================================
-- Solidus — MySQL / MariaDB schema (001_init.sql)
-- DB scaling plan Phase 2 (2.2.0) | Companion to the embedded auto-create DDL
-- in MySqlStorage/TransactionLog (CREATE TABLE IF NOT EXISTS at startup).
--
-- Purpose of this file:
--   1. Human-readable reference for operators (auditing what the mod creates).
--   2. Manual provisioning / DBA review before granting the app role.
--
-- Money columns are DECIMAL(18,2) — EXACT decimal storage, no float drift.
-- UUIDs are CHAR(36) (v1: simplest debugging; BINARY(16) is a later
-- optimization). Timestamps are BIGINT epoch millis (matching SQLite schema —
-- no timezone semantics).
--
-- Recommended dedicated role (least privilege; the mod only ever needs DML):
--   CREATE DATABASE solidus CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--   CREATE USER 'solidus_app'@'%' IDENTIFIED BY '<secret>';
--   GRANT SELECT, INSERT, UPDATE ON solidus.* TO 'solidus_app'@'%';
--   -- No DROP / DELETE on money tables: balances + ledger are authoritative.
--   -- (The mod itself deletes only from pending_notifications during delivery.)
--
-- Minimum versions: MariaDB 10.6+ or MySQL 8.0+ (window functions required;
-- SKIP LOCKED reserved for 2.2.1 sweeps; InnoDB row locks carry all
-- correctness in 2.2.0).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Balances — one row per account (player or system account).
-- 'Solidus Escrow' (all-zero UUID) is the bid-escrow system account; it is
-- excluded from leaderboards by the application and pre-created with 0.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS player_balances (
    uuid CHAR(36) PRIMARY KEY NOT NULL,
    player_name VARCHAR(64) NOT NULL,
    balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    last_updated BIGINT NOT NULL,
    KEY idx_balance_rank (balance DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Idempotency keys (reserved for 2.2.1 cross-server retry-safe operations).
-- result_state stores the JSON outcome of the first execution so a replayed
-- operation can return the stored result instead of executing twice.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS operations (
    op_id CHAR(36) PRIMARY KEY NOT NULL,
    account_uuid CHAR(36),
    op_type VARCHAR(32) NOT NULL,
    request_hash VARCHAR(128),
    result_state TEXT,
    created_at BIGINT NOT NULL,
    KEY idx_operations_account (account_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Transaction ledger — one row per money movement event (audit evidence).
-- Rows are inserted by the application INSIDE the money transaction whenever
-- durability matters (auction settlement, trades): committed money always has
-- matching evidence. See TransactionLog.Type for the 15 codes.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    timestamp BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    player_uuid CHAR(36) NOT NULL,
    player_name VARCHAR(64) NOT NULL,
    target_uuid CHAR(36),
    target_name VARCHAR(64),
    amount DECIMAL(18,2) NOT NULL,
    item_material VARCHAR(128),
    item_quantity INTEGER,
    description TEXT,
    KEY idx_transaction_player (player_uuid, timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Offline notifications — queued when a money event targets an offline player,
-- deleted only by the targeted delivery on next login.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pending_notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    timestamp BIGINT NOT NULL,
    player_uuid CHAR(36) NOT NULL,
    message TEXT NOT NULL,
    KEY idx_notifications_player (player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- AUCTION HOUSE (provisioned for the AuctionStore port — 2.2.1)
-- In 2.2.0 the auction store remains per-server SQLite; these tables mirror
-- auction/AuctionManager.java exactly so the port lands without a migration.
-- ============================================================================

CREATE TABLE IF NOT EXISTS auction_listings (
    listing_id CHAR(36) PRIMARY KEY NOT NULL,
    seller_uuid CHAR(36) NOT NULL,
    seller_name VARCHAR(64) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    item_nbt TEXT,
    price DECIMAL(18,2) NOT NULL,
    listed_timestamp BIGINT NOT NULL,
    expire_timestamp BIGINT NOT NULL,
    status INTEGER NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auction_sold_history (
    listing_id CHAR(36) PRIMARY KEY NOT NULL,
    seller_uuid CHAR(36) NOT NULL,
    seller_name VARCHAR(64) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    price DECIMAL(18,2) NOT NULL,
    buyer_uuid CHAR(36),
    buyer_name VARCHAR(64),
    listed_timestamp BIGINT NOT NULL,
    settled_timestamp BIGINT NOT NULL,
    settled_reason VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auction_bid_state (
    listing_id CHAR(36) PRIMARY KEY NOT NULL,
    start_price DECIMAL(18,2) NOT NULL,
    current_bid DECIMAL(18,2),
    current_bidder_uuid CHAR(36),
    current_bidder_name VARCHAR(64),
    bid_count INTEGER NOT NULL DEFAULT 0,
    extensions_used INTEGER NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auction_bids (
    bid_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    listing_id CHAR(36) NOT NULL,
    bidder_uuid CHAR(36) NOT NULL,
    bidder_name VARCHAR(64) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    bid_timestamp BIGINT NOT NULL,
    KEY idx_bids_listing (listing_id, bid_timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auction_won_items (
    win_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    listing_id CHAR(36) NOT NULL UNIQUE,
    winner_uuid CHAR(36) NOT NULL,
    winner_name VARCHAR(64) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    item_nbt TEXT,
    quantity INTEGER NOT NULL,
    win_price DECIMAL(18,2) NOT NULL,
    won_timestamp BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
