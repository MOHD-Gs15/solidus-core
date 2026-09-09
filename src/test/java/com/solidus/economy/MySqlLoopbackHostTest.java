package com.solidus.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security unit tests for the MySQL cleartext-connection warning (audit
 * SOL-002, CWE-319): {@link MySqlStorage#isLoopbackHost} is what keeps the
 * "balances cross this link in cleartext" warning quiet for the safe
 * single-machine topology and loud for everything else.
 *
 * Pure unit tests — no database connection is opened (MySqlStorage is only
 * class-loaded; its static state is just the SLF4J logger).
 */
@DisplayName("MySqlStorage loopback classification (SOL-002)")
class MySqlLoopbackHostTest {

    @Test
    @DisplayName("localhost and 127.x are loopback - warning stays quiet")
    void loopbackHosts() {
        assertTrue(MySqlStorage.isLoopbackHost("localhost"));
        assertTrue(MySqlStorage.isLoopbackHost("127.0.0.1"));
        assertTrue(MySqlStorage.isLoopbackHost("127.9.9.9"));
        assertTrue(MySqlStorage.isLoopbackHost("::1"));
    }

    @Test
    @DisplayName("classification is case- and whitespace-insensitive")
    void tolerantParsing() {
        assertTrue(MySqlStorage.isLoopbackHost("LOCALHOST"));
        assertTrue(MySqlStorage.isLoopbackHost(" LocalHost "));
        assertTrue(MySqlStorage.isLoopbackHost("  127.0.0.1  "));
    }

    @Test
    @DisplayName("every remote host is NOT loopback - warning fires")
    void remoteHosts() {
        assertFalse(MySqlStorage.isLoopbackHost("db.example.net"));
        assertFalse(MySqlStorage.isLoopbackHost("192.168.1.10"));
        assertFalse(MySqlStorage.isLoopbackHost("10.0.0.5"));
        assertFalse(MySqlStorage.isLoopbackHost("0.0.0.0"));
    }

    @Test
    @DisplayName("null and blank inputs classify as remote (fail-loud)")
    void nullAndBlank() {
        assertFalse(MySqlStorage.isLoopbackHost(null));
        assertFalse(MySqlStorage.isLoopbackHost(""));
        assertFalse(MySqlStorage.isLoopbackHost("   "));
    }

    @Test
    @DisplayName("near-miss addresses do not match the 127. prefix")
    void nearMisses() {
        assertFalse(MySqlStorage.isLoopbackHost("1271.2.3.4"));
        assertFalse(MySqlStorage.isLoopbackHost("12.0.0.1"));
        // ::1 exact only - other IPv6 hosts are remote
        assertFalse(MySqlStorage.isLoopbackHost("::2"));
        assertFalse(MySqlStorage.isLoopbackHost("fe80::1"));
    }
}
