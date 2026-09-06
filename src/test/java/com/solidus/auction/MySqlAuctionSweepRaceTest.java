package com.solidus.auction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The auction sweep race harness (DB scaling plan §7 item 3 — shipped 2.2.4):
 * on a shared-database network EVERY server runs the expiry sweep, so two
 * servers can race the SAME expired listing. The exactly-once row guard
 * inside {@link AuctionManager#archiveAndDeleteListing} is the whole defence
 * — this test fires that PRODUCTION claim method from two concurrent
 * connections repeatedly and asserts: exactly one claim wins, one history
 * row exists, and the listing is gone.
 *
 * <p><b>Activation</b> (self-skipping otherwise): {@code SOLIDUS_TEST_MYSQL_HOST}
 * — same service-container contract as {@code MySqlStorageContractTest}.</p>
 */
@DisplayName("Auction expiry sweep race on MySQL: two servers, one claim (2.2.4)")
public class MySqlAuctionSweepRaceTest {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlAuctionSweepRaceTest.class);

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private static final String MYSQL_URL =
        "jdbc:mariadb://" + HOST + ":" + PORT + "/" + DATABASE;

    private static final UUID SELLER = UUID.fromString("cccccccc-3333-3333-3333-333333333333");

    /** Rounds of the two-server race — each round is one fresh listing. */
    private static final int ROUNDS = 10;

    private static Connection connA;
    private static Connection connB;

    @BeforeAll
    static void connectAndCreateSchema() throws Exception {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — MySQL auction sweep race skipped");
        connA = DriverManager.getConnection(MYSQL_URL, USER, PASSWORD);
        connB = DriverManager.getConnection(MYSQL_URL, USER, PASSWORD);
        try (Statement st = connA.createStatement()) {
            st.execute("DROP TABLE IF EXISTS auction_listings, auction_sold_history, "
                + "auction_bid_state, auction_bids, auction_won_items");
            for (String ddl : AuctionManager.AuctionDialect.MYSQL.statements()) {
                if (ddl != null) {
                    st.execute(ddl);
                }
            }
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (connA != null) connA.close();
        if (connB != null) connB.close();
    }

    private void insertExpiredListing(UUID listingId, double price) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connA.prepareStatement("""
            INSERT INTO auction_listings
            (listing_id, seller_uuid, seller_name, material_name, quantity, item_nbt,
             price, listed_timestamp, expire_timestamp, status)
            VALUES (?, ?, 'RaceSeller', 'minecraft:iron_ingot', 1, NULL, ?, ?, ?, 0)
            """)) {
            ps.setString(1, listingId.toString());
            ps.setString(2, SELLER.toString());
            ps.setDouble(3, price);
            ps.setLong(4, now - 10_000);
            ps.setLong(5, now - 5_000); // already expired
            ps.executeUpdate();
        }
    }

    private int listingCount() throws Exception {
        try (Statement st = connA.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) c FROM auction_listings")) {
            rs.next();
            return rs.getInt("c");
        }
    }

    private int historyCount() throws Exception {
        try (Statement st = connA.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) c FROM auction_sold_history")) {
            rs.next();
            return rs.getInt("c");
        }
    }

    private void wipe() throws Exception {
        try (Statement st = connA.createStatement()) {
            st.execute("DELETE FROM auction_listings");
            st.execute("DELETE FROM auction_sold_history");
            st.execute("DELETE FROM auction_bid_state");
            st.execute("DELETE FROM auction_bids");
            st.execute("DELETE FROM auction_won_items");
        }
    }

    @Test
    @DisplayName("two servers claim the same expired listing concurrently — exactly one settles")
    void concurrentSweepClaimsExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID listingId = UUID.randomUUID();
                insertExpiredListing(listingId, 10.00 + round);
                assertEquals(1, listingCount(), "listing present before the sweep");

                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> fa = pool.submit(() -> {
                    start.await();
                    return AuctionManager.archiveAndDeleteListing(connA, listingId,
                        AuctionManager.SETTLED_EXPIRED_RETURN, null, null,
                        System.currentTimeMillis(), LOG, AuctionManager.AuctionDialect.MYSQL);
                });
                Future<Boolean> fb = pool.submit(() -> {
                    start.await();
                    return AuctionManager.archiveAndDeleteListing(connB, listingId,
                        AuctionManager.SETTLED_EXPIRED_RETURN, null, null,
                        System.currentTimeMillis(), LOG, AuctionManager.AuctionDialect.MYSQL);
                });

                start.countDown();
                boolean wonA = fa.get(30, TimeUnit.SECONDS);
                boolean wonB = fb.get(30, TimeUnit.SECONDS);

                assertTrue(wonA ^ wonB,
                    "round " + round + ": exactly one server must win the claim (A=" + wonA + ", B=" + wonB + ")");
                assertEquals(0, listingCount(), "claimed listing must be deleted");
            }

            assertEquals(ROUNDS, historyCount(), "one archive row per settled listing");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("autoCommit restored on BOTH connections after racing transactions")
    void transactionHygieneAfterRace() throws Exception {
        wipe(); // isolation from the race rounds' archive rows
        UUID listingId = UUID.randomUUID();
        insertExpiredListing(listingId, 99.99);

        assertTrue(AuctionManager.archiveAndDeleteListing(connA, listingId,
            AuctionManager.SETTLED_EXPIRED_RETURN, null, null,
            System.currentTimeMillis(), LOG, AuctionManager.AuctionDialect.MYSQL));
        assertTrue(connA.getAutoCommit(), "connection A back in autocommit");
        assertTrue(connB.getAutoCommit(), "connection B untouched (still autocommit)");
        assertEquals(0, listingCount());
        assertEquals(1, historyCount());
    }
}
