package org.example.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link AudioDevices}. These exercises run against real JVM audio
 * infrastructure and will pass even on machines without physical audio hardware —
 * they simply receive an empty list in that case.
 */
class AudioDevicesTest {

    @Test
    void listCaptureDevices_returnsNonNull() {
        assertNotNull(AudioDevices.listCaptureDevices());
    }

    @Test
    void listCaptureDevices_indexesAreContiguousFromZero() {
        List<AudioDeviceInfo> devices = AudioDevices.listCaptureDevices();
        for (int i = 0; i < devices.size(); i++) {
            assertEquals(i, devices.get(i).index(),
                    "Device at list position " + i + " must carry index " + i);
        }
    }

    @Test
    void listCaptureDevices_allDevicesHaveNonNullNameAndMixerInfo() {
        for (AudioDeviceInfo device : AudioDevices.listCaptureDevices()) {
            assertNotNull(device.name(),      "Device name must not be null");
            assertNotNull(device.mixerInfo(), "MixerInfo must not be null");
        }
    }

    @Test
    void listCaptureDevices_calledTwice_returnsSameSize() {
        assertEquals(
                AudioDevices.listCaptureDevices().size(),
                AudioDevices.listCaptureDevices().size(),
                "Device list size must be stable across successive calls in the same JVM");
    }
}
