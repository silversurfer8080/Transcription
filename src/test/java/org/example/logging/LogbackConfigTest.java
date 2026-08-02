package org.example.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that {@code src/main/resources/logback.xml} loads without ERROR-level
 * statuses, confirming:
 * <ul>
 *   <li>The XML is structurally valid (no typos in element or attribute names).</li>
 *   <li>{@code net.logstash.logback.encoder.LogstashEncoder} is on the runtime
 *       classpath and can be instantiated — i.e. the logstash-logback-encoder
 *       dependency resolved correctly.</li>
 *   <li>Both appenders (CONSOLE + JSON_FILE) and the SizeAndTimeBasedRollingPolicy
 *       start without configuration errors.</li>
 * </ul>
 *
 * <p>Side effect: the RollingFileAppender will attempt to create its log file inside
 * the JUnit {@code @TempDir} (because {@code user.home} is temporarily overridden).
 * This avoids touching {@code ~/Desktop/Flocareer/logs/} during tests and makes the
 * test safe for CI environments where that path does not exist. The temporary
 * directory is cleaned up by JUnit after the test.</p>
 *
 * <p>A fresh, isolated {@link LoggerContext} is used so the test cannot interfere
 * with the live logback context that is already running in the JVM.</p>
 */
class LogbackConfigTest {

    /**
     * Loads {@code logback.xml} from the classpath into a fresh {@link LoggerContext}
     * and asserts that no appender or encoder reports an ERROR-level status.
     *
     * <p>The {@code user.home} system property is temporarily redirected to the JUnit
     * temp directory so the {@code RollingFileAppender} creates its log file there
     * instead of {@code ~/Desktop/Flocareer/logs/}.</p>
     */
    @Test
    void logbackXml_loadsWithoutErrorLevelStatuses(@TempDir Path tempDir) throws Exception {
        String originalHome = System.getProperty("user.home");
        // Redirect user.home so the RollingFileAppender's ${user.home}/Desktop/Flocareer/logs/
        // path resolves inside the temp dir rather than the real Desktop.
        System.setProperty("user.home", tempDir.toString());

        LoggerContext context = new LoggerContext();
        try {
            JoranConfigurator jc = new JoranConfigurator();
            jc.setContext(context);

            URL configUrl = getClass().getClassLoader().getResource("logback.xml");
            assertNotNull(configUrl,
                    "logback.xml must be on the test classpath (check src/main/resources)");

            jc.doConfigure(configUrl);

            // Collect any ERROR-level statuses produced during configuration.
            // Status.ERROR == 2; using >= to catch any future higher severity.
            List<Status> errors = context.getStatusManager()
                    .getCopyOfStatusList()
                    .stream()
                    .filter(s -> s.getLevel() >= Status.ERROR)
                    .toList();

            assertTrue(errors.isEmpty(),
                    "logback.xml must load without ERROR-level statuses. " +
                    "Errors found: " + errors);
        } finally {
            // Always stop the test context (frees rolling-policy threads) and
            // restore user.home so other tests in the same JVM are not affected.
            context.stop();
            System.setProperty("user.home", originalHome);
        }
    }
}
