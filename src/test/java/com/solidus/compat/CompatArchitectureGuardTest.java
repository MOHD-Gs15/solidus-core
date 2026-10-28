package com.solidus.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CompatArchitectureGuardTest — the anti-regression gate for the WS-1
 * isolation rules (audit follow-up to W-3/W-1). This turns the lesson
 * documented in compat/PACKAGE.md into a BUILD BREAKER instead of a
 * comment future contributors can scroll past.
 *
 * <p>It walks the whole {@code src/main/java} tree (via {@code user.dir},
 * the same trick {@code RedisPackagingSmokeTest} uses to walk
 * {@code build/libs}) and fails the build when ANY file OUTSIDE
 * {@code com/solidus/compat/} contains:</p>
 *
 * <ol>
 *   <li>a colored {@code Items.*} constant — {@code *_STAINED_GLASS_PANE}
 *       or {@code *_DYE}: Minecraft 26.3 removed them from {@code Items}
 *       (and the per-color {@code Blocks.*} panes too); the version-free
 *       replacement is {@code com.solidus.compat.Palette}, keyed by
 *       registry ids;</li>
 *   <li>a RAW world drop — any {@code .drop(} call that is not the
 *       {@code CompatDrop.drop(...)} wrapper: the vanilla signature
 *       changed in 26.3 ({@code ServerPlayer#drop(ItemStack, boolean,
 *       Prediction)}), so raw call sites break the compile and bypass the
 *       fail-closed contract;</li>
 *   <li>a colored {@code Blocks.*} constant — same story as (1) on the
 *       block side.</li>
 * </ol>
 *
 * <p>Inside {@code com/solidus/compat/} — and ONLY there — Minecraft
 * internals are allowed: {@code Palette} itself references
 * {@code Items.BARRIER} as the compile-time refuge and
 * {@code CompatDrop} owns the drop shapes.</p>
 *
 * <p>Escape hatch: a line may carry {@code // guard:allow <reason>} to be
 * skipped. Use it sparingly and always with a reason — the test output
 * prints every allowed line so drift stays reviewable.</p>
 *
 * @since 2.4.0
 */
@DisplayName("Compat architecture guard (WS-1): colored constants and raw drops live only in compat")
class CompatArchitectureGuardTest {

    private static final Path MAIN_JAVA = Paths.get(
        System.getProperty("user.dir"), "src", "main", "java");

    private static final String ALLOW_MARKER = "guard:allow";

    /** Colored Items constants — removed from Minecraft 26.3 (measured). */
    private static final Pattern COLORED_ITEM_CONSTANT =
        Pattern.compile("Items\\.[A-Z_]*(STAINED_GLASS_PANE|_DYE)\\b");

    /** Any world drop that is NOT routed through the CompatDrop wrapper. */
    private static final Pattern RAW_WORLD_DROP =
        Pattern.compile("(?<!CompatDrop)\\.drop\\(");

    /** Colored Blocks constants — removed from Minecraft 26.3 (measured). */
    private static final Pattern COLORED_BLOCK_CONSTANT =
        Pattern.compile("Blocks\\.[A-Z_]*STAINED_GLASS_PANE\\b");

    @Test
    @DisplayName("no colored Items/Blocks constants and no raw .drop( outside com/solidus/compat")
    void noColoredConstantsOrRawDropsOutsideCompat() throws IOException {
        assertTrue(Files.isDirectory(MAIN_JAVA),
            "src/main/java must exist relative to user.dir=" + System.getProperty("user.dir"));

        List<String> violations = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        int filesScanned = 0;
        int compatFilesSkipped = 0;

        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String relative = MAIN_JAVA.relativize(file).toString();
                if (relative.replace('\\', '/').startsWith("com/solidus/compat/")) {
                    compatFilesSkipped++;
                    continue;
                }
                filesScanned++;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String where = relative + ":" + (i + 1);
                    if (line.contains(ALLOW_MARKER)) {
                        allowed.add(where + " " + line.trim());
                        continue;
                    }
                    reportIf(violations, where, line, COLORED_ITEM_CONSTANT,
                        "colored Items.* constant (removed in MC 26.3) — use com.solidus.compat.Palette");
                    reportIf(violations, where, line, RAW_WORLD_DROP,
                        "raw world drop — route through com.solidus.compat.CompatDrop");
                    reportIf(violations, where, line, COLORED_BLOCK_CONSTANT,
                        "colored Blocks.* constant (removed in MC 26.3) — use com.solidus.compat.Palette");
                }
            }
        }

        // Sanity margins: the guard must actually be scanning the tree.
        // (58 non-compat files at the time of writing — keep a margin but
        // never so high a lean refactor breaks the guard itself.)
        assertTrue(filesScanned > 40,
            "guard scanned only " + filesScanned + " files — the walk is broken");
        assertTrue(compatFilesSkipped >= 8,
            "guard skipped only " + compatFilesSkipped
                + " compat files — the exclusion rule is broken");

        assertTrue(violations.isEmpty(),
            () -> "WS-1 isolation violated outside com/solidus/compat ("
                + violations.size() + " site(s)):\n  " + String.join("\n  ", violations));

        // Allowed lines are surfaced so suppressions stay reviewable.
        if (!allowed.isEmpty()) {
            System.out.println("CompatArchitectureGuardTest — suppressed (guard:allow) lines:");
            allowed.forEach(a -> System.out.println("  " + a));
        }
    }

    @Test
    @DisplayName("the two isolation classes exist in compat and own their surfaces")
    void isolationClassesExistInsideCompat() {
        Path palette = MAIN_JAVA.resolve(Paths.get("com", "solidus", "compat", "Palette.java"));
        Path compatDrop = MAIN_JAVA.resolve(Paths.get("com", "solidus", "compat", "CompatDrop.java"));

        assertTrue(Files.isRegularFile(palette), "Palette must live in com/solidus/compat");
        assertTrue(Files.isRegularFile(compatDrop), "CompatDrop must live in com/solidus/compat");

        assertDoesNotThrow(() -> {
            String paletteSrc = Files.readString(palette, StandardCharsets.UTF_8);
            assertTrue(paletteSrc.contains("BuiltInRegistries.ITEM"),
                "Palette must resolve through the registry, not constants");
            assertTrue(paletteSrc.contains("Identifier.tryParse"),
                "Palette must key on registry ids");

            String dropSrc = Files.readString(compatDrop, StandardCharsets.UTF_8);
            assertTrue(dropSrc.contains("THREE_ARG_PREDICTION"),
                "CompatDrop must own the 26.3 Prediction strategy");
            assertTrue(dropSrc.contains("TWO_ARG"),
                "CompatDrop must own the 26.1.x 2-arg strategy");
        });
    }

    // -- helpers ------------------------------------------------------------

    private static void reportIf(List<String> violations, String where, String line,
                                 Pattern pattern, String rule) {
        Matcher m = pattern.matcher(line);
        if (m.find()) {
            violations.add(where + " [" + rule + "] " + line.trim());
        }
    }
}
