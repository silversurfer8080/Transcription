package org.example.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the Groq Whisper model registry shown in the UI dropdown: both entries must
 * carry a human label and a distinct, non-blank Groq model id. The turbo id must stay
 * in sync with {@code GroqWhisperProvider}'s default so the no-arg path is unchanged.
 */
class GroqWhisperModelTest {

    @Test
    void bothModels_haveLabelAndModelId() {
        for (GroqWhisperModel m : GroqWhisperModel.values()) {
            assertNotNull(m.label(), () -> m + " label must not be null");
            assertFalse(m.label().isBlank(), () -> m + " label must not be blank");
            assertFalse(m.modelId().isBlank(), () -> m + " modelId must not be blank");
            assertEquals(m.label(), m.toString(), "toString must be the label for the combo box");
        }
    }

    @Test
    void modelIds_areTheExpectedGroqIdentifiers() {
        assertEquals("whisper-large-v3-turbo", GroqWhisperModel.TURBO.modelId());
        assertEquals("whisper-large-v3", GroqWhisperModel.LARGE_V3.modelId());
    }

    @Test
    void modelIds_areDistinct() {
        assertNotEquals(GroqWhisperModel.TURBO.modelId(), GroqWhisperModel.LARGE_V3.modelId());
    }
}
