package org.example.audio;

import javax.sound.sampled.Mixer;

/**
 * Lightweight, UI-friendly description of a capture-capable audio device.
 *
 * <p>Wraps the {@link Mixer.Info} returned by the Java Sound API together with
 * a stable index used for CLI selection (and later, JavaFX dropdowns).
 *
 * @param index        position in the discovered-devices list (0-based)
 * @param name         human-readable mixer name
 * @param description  longer description provided by the OS / driver
 * @param vendor       driver vendor string (often empty on Linux/PipeWire)
 * @param mixerInfo    underlying {@link Mixer.Info} used to open the line
 */
public record AudioDeviceInfo(
        int index,
        String name,
        String description,
        String vendor,
        Mixer.Info mixerInfo) {

    @Override
    public String toString() {
        return "[%d] %s — %s".formatted(index, name, description);
    }
}
