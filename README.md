# Dual-Channel Transcription

A desktop tool for **real-time transcription of two independent audio streams**: capture any two OS-level audio sources simultaneously, transcribe each one separately with a cloud STT provider, and display the results side by side in a lightweight JavaFX UI.

The project is also a deliberate exercise in **Java 21 virtual threads**: every I/O-bound workload (audio capture, STT WebSockets, disk writes, UI events) runs on a virtual thread.

---

## What it does

Two parallel transcription pipelines running independently:

- **Channel A — microphone.** Direct capture from any input device. Audio is buffered both in memory and to disk (per-session WAV) so specific spans can be replayed or analyzed later.
- **Channel B — system audio.** Capture of any application's output, routed in via a virtual audio cable. Live transcript with a copy-to-clipboard button.

Speaker diarization is intentionally avoided — the two streams are kept physically separate at the OS audio-routing layer, which is more reliable and lower-latency than splitting a mixed stream after the fact.

---

## Use cases

- **Remote calls / meetings** — transcribe your own voice and the remote participant independently.
- **Language coaching** — record and analyze your pronunciation against a reference.
- **Podcast / interview recording** — capture host and guest on separate tracks with live captions.
- **Live note-taking** — copy a transcript to the clipboard mid-conversation for pasting elsewhere.

---

## Stack

| Layer | Choice |
|---|---|
| Language | Java 21 (virtual threads, records, pattern matching) |
| Build | Gradle 8.10.2 with Kotlin DSL |
| UI | JavaFX 21 (`org.openjfx.javafxplugin`) |
| Audio capture | Java Sound API (`javax.sound.sampled`) |
| HTTP client | `java.net.http` (standard library) |
| STT provider | Groq Whisper API (batch, free tier) |
| LLM analysis | Groq LLM API (`llama-3.3-70b-versatile`) |
| JSON | Jackson 2.17 |
| Logging | SLF4J + Logback |
| Tests | JUnit 5 |

No paid runtime dependencies. STT and LLM both run on Groq's free tier; the STT layer stays pluggable behind `SpeechToTextProvider` for future offline fallbacks (Vosk, whisper.cpp).

---

## Architecture

### Dual-channel capture by OS routing

Two `AudioCapture` instances bind to different OS mixers. The user picks which mixer is which from a dropdown — `AudioDevices.listCaptureDevices()` enumerates every device that exposes a `TargetDataLine`. Routing a second audio source (e.g. system playback) requires a virtual cable:

| OS | Recommended routing |
|---|---|
| Linux | PipeWire/PulseAudio monitor source or `pactl load-module module-loopback` |
| Windows | VB-Audio Virtual Cable or VoiceMeeter |
| macOS | BlackHole |

### Canonical audio format

A single contract is enforced project-wide: **PCM signed, 16 kHz, 16-bit, mono, little-endian** (`Main.DEFAULT_FORMAT`). This shape is accepted natively by Groq Whisper, AssemblyAI, Vosk and whisper.cpp, avoids resampling cost in the common case, and matches what the WAV writer expects.

### Capture pipeline

`AudioCapture` opens a `TargetDataLine` and runs the read loop on a named virtual thread. Captured PCM is dispatched in ~100 ms chunks via a `Consumer<byte[]>`:

```
AudioCapture ──(byte[] chunks)──► WavFileWriter
                                ► STT provider
                                ► rolling buffer (future)
```

Each channel has its own capture + STT provider on its own virtual thread. The fan-out pattern extends naturally as more consumers are added.

### Pluggable STT

A common interface keeps vendor logic out of the rest of the app:

```java
interface SpeechToTextProvider {
    void start(AudioFormat format, Consumer<TranscriptEvent> onResult);
    void sendAudioChunk(byte[] pcmData);
    void stop();
}
```

`TranscriptEvent` carries: partial-vs-final flag, timestamp, confidence, channel id. Implementations:

- `GroqWhisperProvider` — batch Whisper over HTTPS on Groq's free tier ✅
- `AssemblyAiStreamingProvider` — Universal-Streaming WebSocket (planned)
- `VoskLocalProvider` — offline, via the official Java binding (planned)
- `WhisperCppProvider` — offline, via `whisper.cpp` over JNI or `ProcessBuilder` (planned)

### Streaming WAV writes

`WavFileWriter` writes a 44-byte placeholder RIFF header on construction, appends PCM via `RandomAccessFile`, and patches the two size fields on close — suitable for sessions of arbitrary length. When composing capture + writer with try-with-resources, declare the writer **before** the capture so that close-in-reverse-order stops the capture thread before the header is patched.

---

## Configuration

The Groq API key is read from an environment variable at startup and pre-filled into the UI field (masked). A single key powers both transcription and analysis. Set it before launching:

```bash
# Groq — used for both STT (Whisper) and LLM analysis
export GROQ_API_KEY=your_key_here
```

On Windows (PowerShell):

```powershell
$env:GROQ_API_KEY = "your_key_here"
```

Keys can also be typed directly into the password fields in the UI — they are never logged or written to disk.

---

## Running

Java 21 must be available — the Gradle toolchain resolves it automatically.

```bash
# Compile only
./gradlew compileJava

# Compile + run tests
./gradlew build

# Run the app (JavaFX UI)
./gradlew run

# Run the CLI smoke test (audio capture → WAV, no UI)
# Prints available devices, prompts for an index, records 10 s
./gradlew run --args "3"   # non-interactive: device index 3
```

`stdin` is forwarded into `JavaExec` (configured in `build.gradle.kts`) so the device-selection prompt works under Gradle.

### Package as a native executable (Windows)

```bash
./gradlew packageApp
```

Creates a self-contained `InterviewAssistant.exe` app-image (bundled JRE, no Java required on the target machine) and a Desktop shortcut. Requires a full JDK 14+ with `jpackage`.

---

## Project layout

```
src/main/java/org/example/
  Main.java                       # CLI smoke-test entry point
  audio/
    AudioDeviceInfo.java          # record: index + Mixer.Info wrapper
    AudioDevices.java             # enumerates capture-capable mixers
    AudioCapture.java             # virtual-thread reader + Consumer<byte[]> seam
    AudioResampler.java           # channel down-mix + linear sample-rate conversion
    WavFileWriter.java            # streaming PCM → WAV with header patching
  stt/
    SpeechToTextProvider.java     # provider interface
    TranscriptEvent.java          # transcript result record
    GroqWhisperProvider.java      # batch Whisper on Groq's free tier
  llm/
    GroqClient.java               # Groq LLM calls (answer evaluation + follow-ups)
  ui/
    InterviewApp.java             # main JavaFX application
    PronunciationTab.java         # self-audio coaching tab
    EvaluationTab.java            # LLM answer evaluation tab
    QuestionRef.java              # lightweight reference for the session question list
src/main/resources/
  logback.xml
```

---

## Conventions

- I/O loops use `Thread.ofVirtual().name(...).start(...)` — named virtual threads make logs and thread dumps readable.
- Capture/IO primitives live in `org.example.audio`. STT providers belong in `org.example.stt`. Don't mix transcription concerns into raw capture.
- The canonical audio format is a contract, not a default. Don't change it without auditing every downstream consumer.
- API keys are never logged. Error messages from external APIs are truncated and sanitized before display.
