package com.solidus.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PaletteIdsTest — the pure-text specification of the GUI palette
 * (WS-1 Family A). No Minecraft bootstrap: like the rest of this
 * project's tests, it asserts the CONTRACT by reading the source, so it
 * runs in a plain JVM and still catches the regressions that matter:
 *
 * <ol>
 *   <li>the palette table is exactly the 17 constants that were removed
 *       from {@code Items} in Minecraft 26.3 (15 colored panes + the
 *       READY/NOT-READY dye pair);</li>
 *   <li>every id is the snake_case registry id of its constant — the one
 *       key that survives version churn;</li>
 *   <li>the candidate chains resolve panes and dyes through DIFFERENT
 *       fallback families (a degraded dye must never recover into a glass
 *       pane), ending at the compile-time {@code Items.BARRIER} refuge;</li>
 *   <li>the READY/NOT-READY semantics: {@code lime_dye != gray_dye} —
 *       both as ids and as an explicit distinctness probe in Palette.</li>
 * </ol>
 *
 * @since 2.4.0
 */
@DisplayName("Palette id table (WS-1 Family A) — registry ids are the stable key")
class PaletteIdsTest {

    private static final Path PALETTE_SRC = Paths.get(
        System.getProperty("user.dir"), "src", "main", "java",
        "com", "solidus", "compat", "Palette.java");

    /** public static final Item NAME = pane|dye("exact_id"); */
    private static final Pattern FIELD =
        Pattern.compile("public static final Item (\\w+)\\s*=\\s*(pane|dye)\\(\"([a-z0-9_]+)\"\\);");

    private static final Pattern ENTRY_PUT =
        Pattern.compile("m\\.put\\(\"(\\w+)\",\\s+\"([a-z0-9_]+)\"\\);");

    private static String paletteSource() throws IOException {
        assertTrue(Files.isRegularFile(PALETTE_SRC),
            "Palette.java must exist at " + PALETTE_SRC);
        return Files.readString(PALETTE_SRC, StandardCharsets.UTF_8);
    }

    private static Map<String, String[]> fields(String src) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(src);
        while (m.find()) {
            out.put(m.group(1), new String[]{m.group(2), m.group(3)});
        }
        return out;
    }

    private static Map<String, String> entryTable(String src) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ENTRY_PUT.matcher(src);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    @DisplayName("the palette declares exactly the 17 removed constants (15 panes + 2 dyes)")
    void paletteHasExactlyThe17RemovedConstants() throws IOException {
        Map<String, String[]> f = fields(paletteSource());

        long panes = f.values().stream().filter(v -> v[0].equals("pane")).count();
        long dyes = f.values().stream().filter(v -> v[0].equals("dye")).count();

        assertAll(
            () -> assertEquals(17, f.size(), "palette must cover exactly the 17 removed constants"),
            () -> assertEquals(15, panes, "15 colored panes (white..black)"),
            () -> assertEquals(2, dyes, "exactly LIME_DYE and GRAY_DYE"));
        assertTrue(f.containsKey("LIME_DYE"), "READY indicator must exist");
        assertTrue(f.containsKey("GRAY_DYE"), "NOT-READY indicator must exist");
    }

    @Test
    @DisplayName("every id is the snake_case registry id of its constant name")
    void idsAreSnakeCaseRegistryIdsOfTheirConstants() throws IOException {
        Map<String, String[]> f = fields(paletteSource());
        assertFalse(f.isEmpty(), "field table must not be empty — the FIELD regex must track the source");

        for (Map.Entry<String, String[]> e : f.entrySet()) {
            String constant = e.getKey();
            String kind = e.getValue()[0];
            String id = e.getValue()[1];

            assertTrue(id.matches("[a-z0-9_]+"), constant + ": id must be snake_case, was " + id);
            assertEquals(constant.toLowerCase(java.util.Locale.ROOT), id,
                constant + " must resolve by its own registry id (the stable key)");
            if (kind.equals("dye")) {
                assertTrue(id.endsWith("_dye"), constant + " must key on a dye id");
            } else {
                assertTrue(id.endsWith("_stained_glass_pane"), constant + " must key on a pane id");
            }
        }
    }

    @Test
    @DisplayName("the ENTRY_IDS probe table mirrors the declared fields one-to-one")
    void entryTableMirrorsFields() throws IOException {
        String src = paletteSource();
        Map<String, String[]> f = fields(src);
        Map<String, String> table = entryTable(src);

        assertEquals(f.size(), table.size(), "ENTRY_IDS must list every declared field");
        for (Map.Entry<String, String[]> e : f.entrySet()) {
            assertEquals(e.getValue()[1], table.get(e.getKey()),
                "ENTRY_IDS[" + e.getKey() + "] must match the field's exact id");
        }
    }

    @Test
    @DisplayName("pane and dye candidate chains are separate families, ending at the Items.BARRIER refuge")
    void candidateChainsAreSeparateFamiliesWithBarrierRefuge() throws IOException {
        String src = paletteSource();

        // Pane chain: exact id -> generic stained_glass_pane -> glass_pane.
        assertTrue(src.contains("resolve(exactId, exactId, \"stained_glass_pane\", \"glass_pane\")"),
            "pane chain must be <exact> -> stained_glass_pane -> glass_pane (in that order)");
        // Dye chain: exact id -> generic dye.
        assertTrue(src.contains("resolve(exactId, exactId, \"dye\")"),
            "dye chain must be <exact> -> dye (never a pane candidate)");
        // Both families end at the compile-time refuge (1 of the 13 constants that survived 26.3).
        assertTrue(src.contains("return Items.BARRIER;"),
            "both families must end at the compile-time Items.BARRIER refuge");
        // Per-candidate Throwable guard (CompatProbes lesson).
        assertTrue(src.contains("catch (Throwable t)"),
            "every candidate must be guarded with catch (Throwable), not catch (Exception)");
    }

    @Test
    @DisplayName("READY/NOT-READY semantics: lime_dye and gray_dye are distinct by construction and by probe")
    void readyNotReadyDyesAreDistinctByConstructionAndProbe() throws IOException {
        String src = paletteSource();
        Map<String, String[]> f = fields(src);

        String limeId = f.get("LIME_DYE")[1];
        String grayId = f.get("GRAY_DYE")[1];

        assertNotEquals(limeId, grayId,
            "the two indicator dyes must key on DIFFERENT registry ids");

        assertTrue(src.contains("probeDistinctReadyIndicators"),
            "Palette must expose the distinctness probe for the startup report");
        assertTrue(src.contains("lime != null && gray != null && lime != gray"),
            "the distinctness probe must compare resolved ITEM identity, not just ids");
    }

    @Test
    @DisplayName("no file outside compat references the colored constants (spec §3.2 rule stated here too)")
    void paletteClassJavadocCarriesTheIsolationRule() throws IOException {
        String src = paletteSource();
        assertTrue(src.contains("com.solidus.compat") && src.contains("CompatArchitectureGuardTest"),
            "Palette must document the enforcement path of the isolation rule");
    }
}
