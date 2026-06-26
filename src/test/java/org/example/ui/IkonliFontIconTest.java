package org.example.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies Ikonli FontIcon instantiation, icon-size configuration, and the
 * automatic addition of the {@code ikonli-font-icon} style class.
 *
 * <p>Tests that create {@link FontIcon} objects require the JavaFX toolkit.
 * They are <em>skipped</em> (not failed) when the toolkit cannot be started,
 * so the build stays green on headless CI agents.
 *
 * <p>Two tests ({@link #ikonliJavafx_classIsLoadable()} and
 * {@link #ikonliMaterialDesign2_packIsLoadable()}) are pure classpath checks
 * and never need the toolkit.
 */
class IkonliFontIconTest {

    private static volatile boolean javaFxAvailable = false;

    @BeforeAll
    static void initJavaFX() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException e) {
            // Toolkit was already started by a previous test class — that is fine.
            javaFxAvailable = true;
            // Prevent the platform from shutting down automatically when no windows remain.
            Platform.setImplicitExit(false);
            return;
        } catch (Exception e) {
            // No display or other init failure — JavaFX tests will be skipped.
            return;
        }
        javaFxAvailable = ready.await(5, TimeUnit.SECONDS);
        if (javaFxAvailable) {
            Platform.setImplicitExit(false);
        }
    }

    // ── Classpath / dependency presence (no JavaFX toolkit required) ──────────

    @Test
    void ikonliJavafx_classIsLoadable() throws ClassNotFoundException {
        // Fails if org.kordamp.ikonli:ikonli-javafx is absent from the classpath.
        Class.forName("org.kordamp.ikonli.javafx.FontIcon");
    }

    @Test
    void ikonliMaterialDesign2_packIsLoadable() throws ClassNotFoundException {
        // Fails if org.kordamp.ikonli:ikonli-materialdesign2-pack is absent.
        // MaterialDesignP covers PLAY, PLUS, POWER — all used by the app.
        Class.forName("org.kordamp.ikonli.materialdesign2.MaterialDesignP");
    }

    // ── Icon-literal naming convention (no JavaFX toolkit required) ───────────

    /**
     * All icon literals referenced in {@code InterviewApp.java} must follow the
     * MDI2 pattern {@code mdi2<first-letter-of-pack>-<icon-name>}.
     * A naming mismatch would cause {@link FontIcon} to throw at runtime.
     */
    @ParameterizedTest(name = "literal format valid: {0}")
    @ValueSource(strings = {
        "mdi2p-power",
        "mdi2p-play",
        "mdi2s-stop",
        "mdi2p-plus",
        "mdi2b-broom",
        "mdi2t-trash-can-outline",
        "mdi2f-folder-open",
        "mdi2r-robot",
        "mdi2c-comment-question-outline",
        "mdi2c-content-copy",
        "mdi2r-record-circle",
        "mdi2c-check-circle"
    })
    void iconLiteral_matchesMdi2NamingPattern(String literal) {
        // Pattern: mdi2 + one lowercase letter (category initial) + '-' + kebab name
        assertTrue(literal.matches("mdi2[a-z]-[a-z0-9-]+"),
                "\"" + literal + "\" does not match the MDI2 pattern mdi2X-icon-name");
    }

    // ── FontIcon instantiation for every app icon literal (requires toolkit) ──

    /**
     * Creating a {@link FontIcon} with an unrecognised literal throws immediately
     * because Ikonli resolves the code in the constructor via ServiceLoader.
     * A failure here means either the pack JAR is missing or the literal is wrong.
     *
     * <p>Note: {@code mdi2b-broom} is flagged as a known risk in {@code changes.md}.
     * If this parameterised test fails for that literal, the literal must be
     * substituted with the nearest match per spec §10.
     */
    @ParameterizedTest(name = "FontIcon(\"{0}\") succeeds")
    @ValueSource(strings = {
        "mdi2p-power",
        "mdi2p-play",
        "mdi2s-stop",
        "mdi2p-plus",
        "mdi2b-broom",
        "mdi2t-trash-can-outline",
        "mdi2f-folder-open",
        "mdi2r-robot",
        "mdi2c-comment-question-outline",
        "mdi2c-content-copy",
        "mdi2r-record-circle",
        "mdi2c-check-circle"
    })
    void fontIcon_validLiteral_createsWithoutException(String literal) throws InterruptedException {
        assumeTrue(javaFxAvailable, "JavaFX toolkit unavailable — skipping FontIcon instantiation test");

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<FontIcon>  iconRef  = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                iconRef.set(new FontIcon(literal));
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS),
                "JavaFX thread timed out for literal: " + literal);
        assertNull(errorRef.get(),
                "new FontIcon(\"" + literal + "\") must not throw. " +
                "If mdi2b-broom fails, substitute the literal per spec §10. " +
                "Error: " + errorRef.get());
        assertNotNull(iconRef.get(),
                "FontIcon must be non-null for literal: " + literal);
    }

    // ── FontIcon properties (requires toolkit) ────────────────────────────────

    @Test
    void fontIcon_setIconSize16_getIconSize_returns16() throws InterruptedException {
        // The InterviewApp.icon() helper always calls fi.setIconSize(16).
        // This test verifies Ikonli correctly stores and retrieves the size.
        assumeTrue(javaFxAvailable, "JavaFX toolkit unavailable");

        AtomicReference<Integer> sizeRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FontIcon fi = new FontIcon("mdi2p-play");
                fi.setIconSize(16);
                sizeRef.set(fi.getIconSize());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "FX thread timed out");
        assertNull(errorRef.get(), "Unexpected error: " + errorRef.get());
        assertEquals(16, sizeRef.get(),
                "FontIcon.getIconSize() must return 16 after setIconSize(16)");
    }

    @Test
    void fontIcon_constructor_addsIkonliFontIconStyleClass() throws InterruptedException {
        // Ikonli registers 'ikonli-font-icon' on every FontIcon in the constructor.
        // app.css depends on this class to tint icons: .button .ikonli-font-icon { ... }
        assumeTrue(javaFxAvailable, "JavaFX toolkit unavailable");

        AtomicReference<Boolean> hasClass = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FontIcon fi = new FontIcon("mdi2p-power");
                hasClass.set(fi.getStyleClass().contains("ikonli-font-icon"));
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "FX thread timed out");
        assertNull(errorRef.get(), "Unexpected error: " + errorRef.get());
        assertTrue(hasClass.get(),
                "FontIcon must automatically add 'ikonli-font-icon' to its style-class list;" +
                " otherwise app.css icon-tinting rules will have no effect.");
    }

    @Test
    void fontIcon_defaultIconSize_isPositive() throws InterruptedException {
        // Sanity-check: even before setIconSize() the icon must have a positive size.
        assumeTrue(javaFxAvailable, "JavaFX toolkit unavailable");

        AtomicReference<Integer> sizeRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FontIcon fi = new FontIcon("mdi2s-stop");
                sizeRef.set(fi.getIconSize());
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "FX thread timed out");
        assertNotNull(sizeRef.get());
        assertTrue(sizeRef.get() > 0,
                "FontIcon default icon size must be positive; got: " + sizeRef.get());
    }
}
