package org.example.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that app.css is accessible on the classpath and contains the exact
 * color-palette variables, style-class selectors, and structural rules required
 * by the UI-modernisation spec (§5 and §6).
 *
 * These tests do NOT require a running JavaFX toolkit; they read the file purely
 * as a text resource.
 */
class AppStylesheetTest {

    private static final String STYLESHEET_PATH = "/styles/app.css";
    /** Cached full text of app.css — loaded once for all test methods. */
    private static String cssContent;

    @BeforeAll
    static void loadCssContent() throws Exception {
        try (InputStream is = AppStylesheetTest.class.getResourceAsStream(STYLESHEET_PATH)) {
            assertNotNull(is,
                    "app.css must be accessible on the classpath at " + STYLESHEET_PATH +
                    ". Check that src/main/resources/styles/app.css is present and" +
                    " processResources has run.");
            cssContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Resource accessibility ─────────────────────────────────────────────────

    @Test
    void stylesheet_getResource_returnsNonNullUrl() {
        URL url = AppStylesheetTest.class.getResource(STYLESHEET_PATH);
        assertNotNull(url,
                "Class.getResource(\"" + STYLESHEET_PATH + "\") must return a non-null URL;" +
                " verify that the file was copied by processResources.");
    }

    @Test
    void stylesheet_content_isNonEmpty() {
        assertFalse(cssContent.isBlank(), "app.css must not be empty");
    }

    // ── STYLESHEET constant in InterviewApp ────────────────────────────────────

    @Test
    void interviewApp_stylesheetConstant_hasExpectedPath() throws Exception {
        Field f = InterviewApp.class.getDeclaredField("STYLESHEET");
        f.setAccessible(true);
        String value = (String) f.get(null);
        assertEquals("/styles/app.css", value,
                "InterviewApp.STYLESHEET must equal \"/styles/app.css\"");
    }

    @Test
    void interviewApp_stylesheetConstant_resolvesToExistingResource() throws Exception {
        Field f = InterviewApp.class.getDeclaredField("STYLESHEET");
        f.setAccessible(true);
        String constant = (String) f.get(null);
        URL url = InterviewApp.class.getResource(constant);
        assertNotNull(url,
                "The value of STYLESHEET (\"" + constant + "\") must resolve to" +
                " an actual classpath resource; the applyStylesheet() helper will be a no-op otherwise.");
    }

    // ── Color palette variables — all 14 must appear in the .root block ───────

    /**
     * Spec §5.1 defines 14 looked-up color variables that must be declared on
     * {@code .root} so every node in the scene graph can inherit them.
     */
    @ParameterizedTest(name = "palette variable declared: {0}")
    @ValueSource(strings = {
        "-app-primary",
        "-app-primary-hover",
        "-app-primary-pressed",
        "-app-secondary",
        "-app-accent",
        "-app-surface",
        "-app-card",
        "-app-border",
        "-app-text",
        "-app-text-muted",
        "-app-danger",
        "-app-danger-hover",
        "-app-amber",
        "-app-tint"
    })
    void stylesheet_rootBlock_declaresColorVariable(String variable) {
        assertTrue(cssContent.contains(variable + ":"),
                "app.css must declare the color variable \"" + variable + ":\" inside .root");
    }

    @Test
    void stylesheet_appPrimary_isCorrectHex() {
        // Spec §5.1: -app-primary = #2563EB (Tailwind blue-600)
        String line = firstLineContaining(cssContent, "-app-primary:");
        assertNotNull(line, "-app-primary must be declared");
        assertTrue(line.contains("#2563EB"),
                "-app-primary must be set to #2563EB; found: " + line.trim());
    }

    @Test
    void stylesheet_appDanger_isCorrectHex() {
        // Spec §5.1: -app-danger = #DC2626 (Tailwind red-600)
        assertTrue(cssContent.contains("#DC2626"),
                "-app-danger must include #DC2626");
    }

    @Test
    void stylesheet_appAmber_isCorrectHex() {
        // Spec §5.1: -app-amber = #F59E0B (Tailwind amber-500)
        assertTrue(cssContent.contains("#F59E0B"),
                "-app-amber must be #F59E0B for star-rating label colour");
    }

    @Test
    void stylesheet_appSurface_isCorrectHex() {
        // Spec §5.1: -app-surface = #F1F5F9 (Tailwind slate-100) — window background
        assertTrue(cssContent.contains("#F1F5F9"),
                "-app-surface must be #F1F5F9");
    }

    // ── Style-class selectors ─────────────────────────────────────────────────

    /**
     * Every CSS class assigned to controls in {@code InterviewApp.java} (§6.5)
     * must have a corresponding rule in app.css.
     */
    @ParameterizedTest(name = "selector present: {0}")
    @ValueSource(strings = {
        ".btn-primary",
        ".btn-secondary",
        ".btn-danger",
        ".btn-icon",
        ".btn-ghost",
        ".section-label",
        ".partial-label",
        ".rating-label",
        ".source-label",
        ".form-label",
        ".muted-label",
        ".resize-handle"
    })
    void stylesheet_definesRequiredStyleClass(String selector) {
        assertTrue(cssContent.contains(selector),
                "app.css must define style-class selector \"" + selector + "\"");
    }

    @Test
    void stylesheet_definesIkonliFontIconSelector() {
        // Ikonli adds 'ikonli-font-icon' to every FontIcon's style-class list.
        // The CSS uses this hook to tint icons per button variant.
        assertTrue(cssContent.contains(".ikonli-font-icon"),
                "app.css must define at least one .ikonli-font-icon rule for icon tinting");
    }

    // ── Button pseudo-class states (FR-2) ─────────────────────────────────────

    @Test
    void stylesheet_button_hasHoverState() {
        assertTrue(cssContent.contains(".button:hover"),
                "app.css must define .button:hover to show a hover tint");
    }

    @Test
    void stylesheet_button_hasArmedState() {
        assertTrue(cssContent.contains(".button:armed"),
                "app.css must define .button:armed (pressed-down feedback)");
    }

    @Test
    void stylesheet_button_hasFocusedState() {
        assertTrue(cssContent.contains(".button:focused"),
                "app.css must define .button:focused with a visible focus ring");
    }

    @Test
    void stylesheet_button_hasDisabledState() {
        assertTrue(cssContent.contains(".button:disabled"),
                "app.css must define .button:disabled (reduced opacity)");
    }

    // ── Rounded corners (FR-2, FR-3) ─────────────────────────────────────────

    @Test
    void stylesheet_button_hasBackgroundRadius8px() {
        // Spec §2 FR-2 and §5.2: all buttons must have ≥8px rounded corners
        assertTrue(cssContent.contains("-fx-background-radius: 8px"),
                "app.css must set -fx-background-radius: 8px on .button for rounded corners");
    }

    @Test
    void stylesheet_btnIcon_hasCircularRadius17px() {
        // Power button: 34×34 px; 17px radius makes it a circle (spec §6.5, §5.2)
        assertTrue(cssContent.contains("-fx-background-radius: 17px"),
                ".btn-icon must use 17px background-radius for the circular power button");
    }

    @Test
    void stylesheet_inputs_haveBorderRadius8px() {
        // Spec FR-3: text fields and combo boxes must have rounded corners
        assertTrue(cssContent.contains("-fx-border-radius: 8px"),
                "Input controls (.text-field etc.) must have -fx-border-radius: 8px");
    }

    // ── Focus colour wiring (FR-3) ────────────────────────────────────────────

    @Test
    void stylesheet_focusedInput_showsPrimaryBorderColor() {
        // The grouped :focused rule must switch border to -app-primary
        assertTrue(cssContent.contains("-fx-border-color: -app-primary"),
                "Focused input controls must display -app-primary as border colour");
    }

    @Test
    void stylesheet_root_setsFxFocusColorToPrimary() {
        // -fx-focus-color on .root makes the default JavaFX focus ring match the palette
        assertTrue(cssContent.contains("-fx-focus-color: -app-primary"),
                ".root must set -fx-focus-color: -app-primary");
    }

    // ── Per-variant icon tinting ──────────────────────────────────────────────

    @Test
    void stylesheet_btnPrimary_tintsFontIconWhite() {
        // Icons on filled-blue buttons must be white (spec §5.2)
        assertTrue(cssContent.contains(".btn-primary .ikonli-font-icon"),
                "app.css must define .btn-primary .ikonli-font-icon for white icon tinting");
    }

    @Test
    void stylesheet_btnDanger_tintsFontIconWithDangerColor() {
        assertTrue(cssContent.contains(".btn-danger .ikonli-font-icon"),
                "app.css must define .btn-danger .ikonli-font-icon rule");
    }

    @Test
    void stylesheet_btnIconBtnDanger_isFilledRedCombination() {
        // The power button uses BOTH .btn-icon and .btn-danger together (spec §6.5, §5.2)
        assertTrue(cssContent.contains(".btn-icon.btn-danger"),
                "app.css must define the .btn-icon.btn-danger compound selector" +
                " for the filled-red circular power button");
    }

    // ── Structural selectors ─────────────────────────────────────────────────

    @Test
    void stylesheet_definesTitledPaneTitleSelector() {
        // TitledPane title bars are styled with a tint background (spec §5.2)
        assertTrue(cssContent.contains(".titled-pane > .title"),
                "app.css must style the .titled-pane > .title bar");
    }

    @Test
    void stylesheet_definesResizeHandleHoverState() {
        // wrapResizableRow handle uses .resize-handle (spec §6.5)
        assertTrue(cssContent.contains(".resize-handle:hover"),
                "app.css must define .resize-handle:hover");
    }

    @Test
    void stylesheet_definesAppDialogSelector() {
        // Optional Alert polish (spec §6.7)
        assertTrue(cssContent.contains(".app-dialog"),
                "app.css must define .app-dialog for Alert dialog polish");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static String firstLineContaining(String text, String pattern) {
        for (String line : text.split("\n")) {
            if (line.contains(pattern)) return line;
        }
        return null;
    }
}
