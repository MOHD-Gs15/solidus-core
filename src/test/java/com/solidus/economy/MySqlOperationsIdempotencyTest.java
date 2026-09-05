package com.solidus.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI-gated tests for the {@code operations} idempotency wiring (2.2.1 —
 * DB scaling plan §11 item 4): a transfer executed with an {@code opId}
 * must move money AT MOST ONCE per id, even when replayed.
 *
 * <p>Activation (self-skipping otherwise): {@code SOLIDUS_TEST_MYSQL_HOST} —
 * same service container contract as {@code MySqlStorageContractTest}.</p>
 */
@DisplayName("Operations-table idempotent transfers (2.2.1)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MySqlOperationsIdempotencyTest {

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private static final UUID SENDER = UUID.fromString("11111111-aaaa-2222-bbbb-333333333333");
    private static final UUID RECEIVER = UUID.fromString("44444444-cccc-5555-dddd-666666666666");

    private MySqlStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — idempotency tests skipped");
        storage = new MySqlStorage(new StorageConfig.MySqlSettings(
            HOST, PORT, DATABASE, USER, PASSWORD, 4, 5000, false));
        storage.initialize();
        try (Connection conn = storage.borrowConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM player_balances WHERE uuid IN ('" + SENDER + "', '" + RECEIVER + "')");
            st.execute("DELETE FROM operations");
        }
        storage.setBalance(SENDER, "SenderS", 1000.00).get(5, TimeUnit.SECONDS);
        storage.setBalance(RECEIVER, "ReceiverR", 0.0).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
    }

    private double balance(UUID uuid) throws Exception {
        return storage.getBalance(uuid, "").get(5, TimeUnit.SECONDS);
    }

    @Test
    @Order(1)
    @DisplayName("first execution moves the money; a replay returns the recorded result and moves NOTHING")
    void transferThenReplay() throws Exception {
        UUID opId = UUID.randomUUID();
        var first = storage.transferAtomicWithLedger(opId, "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 100.00, List.of())
            .get(10, TimeUnit.SECONDS);
        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, first.status());
        assertEquals(900.00, balance(SENDER), 0.0001);
        assertEquals(100.00, balance(RECEIVER), 0.0001);

        var replay = storage.transferAtomicWithLedger(opId, "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 100.00, List.of())
            .get(10, TimeUnit.SECONDS);
        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, replay.status(),
            "the replay must see the recorded SUCCESS outcome");
        assertEquals(first.senderNewBalance(), replay.senderNewBalance(), 0.0001);
        assertEquals(first.receiverNewBalance(), replay.receiverNewBalance(), 0.0001);
        assertEquals(900.00, balance(SENDER), 0.0001, "replay must NOT move money again");
        assertEquals(100.00, balance(RECEIVER), 0.0001, "replay must NOT move money again");
    }

    @Test
    @Order(2)
    @DisplayName("a different opId executes again (idempotency is per-op, not global)")
    void differentOpIdExecutes() throws Exception {
        storage.transferAtomicWithLedger(UUID.randomUUID(), "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 10.00, List.of())
            .get(10, TimeUnit.SECONDS);
        storage.transferAtomicWithLedger(UUID.randomUUID(), "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 10.00, List.of())
            .get(10, TimeUnit.SECONDS);
        assertEquals(980.00, balance(SENDER), 0.0001);
        assertEquals(20.00, balance(RECEIVER), 0.0001);
    }

    @Test
    @Order(3)
    @DisplayName("failed transfers are recorded too — an insufficient-funds replay does not re-execute")
    void failedOutcomeRecorded() throws Exception {
        UUID opId = UUID.randomUUID();
        var first = storage.transferAtomicWithLedger(opId, "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 99_999.00, List.of())
            .get(10, TimeUnit.SECONDS);
        assertEquals(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS, first.status());

        var replay = storage.transferAtomicWithLedger(opId, "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 99_999.00, List.of())
            .get(10, TimeUnit.SECONDS);
        assertEquals(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS, replay.status(),
            "replay sees the recorded failure (deterministic anyway)");
        assertEquals(1000.00, balance(SENDER), 0.0001);
    }

    @Test
    @Order(4)
    @DisplayName("null opId keeps plain (non-idempotent) semantics")
    void nullOpIdIsPlain() throws Exception {
        var outcome = storage.transferAtomicWithLedger(null, "PAY",
                SENDER, "SenderS", RECEIVER, "ReceiverR", 5.00, List.of())
            .get(10, TimeUnit.SECONDS);
        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
        assertEquals(995.00, balance(SENDER), 0.0001);
    }
}
