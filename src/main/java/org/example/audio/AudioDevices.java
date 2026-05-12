package org.example.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers capture-capable audio devices visible to the JVM.
 *
 * <p>The Java Sound API exposes mixers for every input <em>and</em> output
 * device the OS reports. We filter to the ones that expose a
 * {@link TargetDataLine} (i.e. can be read from), which is what we need for
 * both microphone capture and virtual-cable monitor sources.
 */
public final class AudioDevices {

    private AudioDevices() {}

    /**
     * Lists every mixer that supports at least one {@link TargetDataLine}.
     *
     * <p>On Linux/PipeWire the same physical device often appears multiple
     * times (one entry per ALSA pcm name plus a "default" alias) — we don't
     * try to deduplicate, since the user is the one picking which entry
     * actually routes the stream they want.
     */
    public static List<AudioDeviceInfo> listCaptureDevices() {
        List<AudioDeviceInfo> result = new ArrayList<>();
        Mixer.Info[] all = AudioSystem.getMixerInfo();
        int idx = 0;
        for (Mixer.Info info : all) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (supportsCapture(mixer)) {
                result.add(new AudioDeviceInfo(
                        idx++,
                        info.getName(),
                        info.getDescription(),
                        info.getVendor(),
                        info));
            }
        }
        return result;
    }

    private static boolean supportsCapture(Mixer mixer) {
        for (Line.Info li : mixer.getTargetLineInfo()) {
            if (li instanceof DataLine.Info dli
                    && TargetDataLine.class.isAssignableFrom(dli.getLineClass())) {
                return true;
            }
        }
        return false;
    }
}
