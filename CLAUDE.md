# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

Desktop tool for **dual-channel real-time transcription**. Two distinct purposes:

1. **Self-audio coaching:** capture the user's mic, transcribe, allow marking spans for later analysis (LLM critique + TTS reference + stored explanation).
2. **Remote participant transcription:** capture system audio (the remote participant's speech routed through a virtual cable) and show live transcript with a copy button.

The project is also a deliberate exercise in **Java 21 virtual threads**, so favor `Thread.ofVirtual()` / `newVirtualThreadPerTaskExecutor()` / `StructuredTaskScope` for any I/O-bound workload (audio capture loops, STT WebSockets, disk writes, UI events).

## Current phase

**Phase 1 (Foundation) is complete.** Subsequent phases — see `PROJECT_SPEC.md` §"Plano de implementação por fases":

- Phase 2: `SpeechToTextProvider` interface + `DeepgramStreamingProvider` (WebSocket to `wss://api.deepgram.com/v1/listen`) + minimal JavaFX UI for the candidate channel.
- Phase 3: dual capture (own mic + candidate) + dual-pane UI + per-session JSON transcript.
- Phase 4: rolling in-memory buffer, click-to-mark span, Anthropic Claude analysis, TTS, structured per-session output under `~/interview-assistant-data/sessions/<date>_<candidate>/`.
- Phase 5: AssemblyAI provider, local Vosk fallback, JNativeHook hotkeys, always-on-top.

Don't add scaffolding for later phases unless the current task explicitly calls for it.

## Commands

Gradle wrapper is **8.10.2** (required for Java 21 runtime support). Java toolchain is 21.

```bash
./gradlew compileJava           # compile only
./gradlew build                 # compile + test
./gradlew test                  # JUnit 5
./gradlew test --tests <FQCN>   # single test class
./gradlew run                   # interactive CLI: prompts for device index
./gradlew run --args "3"        # non-interactive: pick device index 3
```

`run` forwards `System.in` (configured in `build.gradle.kts`) so the device-selection prompt works under Gradle. JavaFX plugin is wired up but not yet exercised — the Phase 1 entry point is a CLI smoke test.

## Architecture

### Dual-channel audio by OS routing, not diarization

The two streams (interviewer mic / candidate playback) are kept physically separate at the OS level — we do **not** run speaker diarization. The user picks which mixer is "my mic" and which is "candidate" from the OS-discovered list. Virtual cables provide the candidate stream:

- Linux: PulseAudio/PipeWire monitor source or `module-loopback`
- Windows: VB-Audio Virtual Cable / VoiceMeeter
- macOS: BlackHole

`AudioDevices.listCaptureDevices()` enumerates anything with a `TargetDataLine`; the user-visible boundary is `AudioDeviceInfo` (record carrying `Mixer.Info`). The method intentionally does **not** deduplicate — on Linux/PipeWire, the same physical device appears under multiple ALSA PCM names (including "default" aliases), and the user picks whichever entry actually routes the desired stream.

### Canonical audio format

`Main.DEFAULT_FORMAT` — **PCM signed, 16 kHz, 16-bit, mono, little-endian** — is the project-wide contract. Picked because Deepgram, AssemblyAI, Vosk and whisper.cpp all accept it natively. Don't change it lightly: every downstream component (STT providers, WAV writer, future rolling buffer) assumes this shape.

### Capture pipeline

`AudioCapture` opens a `TargetDataLine` and runs the read loop on a **named virtual thread**. Chunks default to ~100 ms (3200 bytes at the canonical format) — the balance point between syscall overhead and live-transcription latency. Each chunk is dispatched to a `Consumer<byte[]>`, which is the seam where downstream consumers (WAV writer, STT provider, rolling buffer) plug in. The read loop always copies the buffer before dispatch (consumer may queue it asynchronously). The line's internal buffer is `chunkSize * 4` so brief consumer stalls don't drop samples. `stop()` closes the line to unblock the in-flight `read()`, then joins the reader with a 2-second timeout.

Each audio channel will have its own `AudioCapture` + its own STT provider instance, each on its own virtual thread — that's the fan-out pattern the rest of the app extends.

### Streaming WAV writes

`WavFileWriter` writes a 44-byte placeholder header on construction, appends PCM via `RandomAccessFile`, and patches the two RIFF/data size fields on `close()`. `AudioSystem.write` is unsuitable here because it requires the full audio length up front, and interview sessions can run an hour+. The writer enforces little-endian PCM at construction. `write()` and `close()` are `synchronized` — safe to call from the capture virtual thread while the UI thread triggers close.

### Try-with-resources order

In `Main`, the `WavFileWriter` is declared **before** `AudioCapture` so that close-in-reverse-order stops capture (and joins the reader) **before** the header is patched. Preserve this ordering when composing similar resources elsewhere — late chunks dispatched after the writer closes would silently drop.

### STT provider abstraction (Phase 2 onward)

The planned `SpeechToTextProvider` interface (see `PROJECT_SPEC.md`) is the swap-point for vendor flexibility:

```java
interface SpeechToTextProvider {
    void start(AudioFormat format, Consumer<TranscriptEvent> onResult);
    void sendAudioChunk(byte[] pcmData);
    void stop();
}
```

`TranscriptEvent` carries: partial-vs-final flag, timestamp, confidence, channel id. Concrete providers live behind this interface so vendor lock-in stays out of the rest of the app — Deepgram first, AssemblyAI next, Vosk/whisper.cpp later as offline fallbacks.

### Dependencies already on the classpath

`jackson-databind 2.17.2` is already declared — available for JSON transcript serialization in Phase 3 without adding a new dependency. JUnit 5 is wired up; test source directory exists but is empty (Phase 1 is a manual smoke test only).

## Conventions

- `org.example.audio` package owns the capture/IO primitives. Keep STT providers in a sibling package (`org.example.stt` suggested) when Phase 2 starts — don't mix transcription with raw capture.
- Logging: SLF4J via Logback. Config in `src/main/resources/logback.xml`.
- Use `Thread.ofVirtual().name(...).start(...)` for I/O loops — the named threads make logs and dumps readable.
- The tool must not automate or interact with any third-party web platform. It only captures audio at the OS level. Don't propose browser automation, page scraping, or DOM injection.
