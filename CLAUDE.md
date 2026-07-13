# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

Desktop tool for **interview assistance built on dual-channel real-time transcription**. Origins and long-term goals (see `PROJECT_SPEC.md`) span two purposes:

1. **Self-audio coaching:** capture the user's mic, transcribe, mark spans for later analysis (LLM critique + TTS reference + stored explanation).
2. **Remote participant transcription:** capture system audio (a remote participant's speech routed through a virtual cable) and show a live transcript.

What actually shipped is an **interview-evaluation workflow**: the candidate channel is captured and transcribed live into per-question panels, and each answer is scored by an LLM (star rating + prose critique + follow-up questions). The UI is in **Brazilian Portuguese**; the AI's evaluation output is in **English**.

The project is also a deliberate exercise in **Java 21 virtual threads**, so favor `Thread.ofVirtual()` / `newVirtualThreadPerTaskExecutor()` / `StructuredTaskScope` for any I/O-bound workload (audio capture loops, STT/LLM HTTP calls, disk writes).

## Current phase

**Phases 1–3 are complete** (foundation, STT provider + JavaFX UI, dual-capture plumbing + per-session transcript). Working today:

- **STT (user-selectable):** the "Transcrição" dropdown picks one of three backends — `VoskSttProvider` (offline, unlimited, no key — the recommended default and the fix for Groq's daily-limit exhaustion + Whisper's "thank you" silence hallucinations), `GroqWhisperProvider` (batch Whisper on Groq's free tier), or `GeminiSttProvider` (Gemini multimodal transcription on its own free tier, independent of Groq). The two HTTP backends share a common `BatchWindowSttProvider` base; Vosk is streaming. Deepgram was removed once its free credits ran out.
- **LLM (user-selectable):** the "Análise" dropdown picks an `LlmProvider` — Groq (`llama-3.3-70b-versatile`), Google Gemini (`gemini-2.5-flash`), or Cerebras (`gpt-oss-120b`). All three speak the OpenAI chat-completions wire format, so `GroqClient` calls them unchanged (only endpoint + model + key + reasoning settings differ). STT and LLM keys are entered per provider in a collapsible "Chaves de API" panel, prefilled from `GROQ_API_KEY` / `GEMINI_API_KEY` / `CEREBRAS_API_KEY`.
- **UI:** `InterviewApp` — a single-window interview tab with per-question panels, live transcription into the candidate column, inline AI analysis, star rating, **interactive multi-turn follow-up rounds** (the AI proposes 3 follow-ups as radio options; picking one opens a new answer sub-panel that captures the next round, and re-analysis scores the whole exchange), and plain-text session persistence.

Not yet built (see `PROJECT_SPEC.md` §"Plano de implementação por fases"): the interviewer's own mic channel + dual-pane layout, rolling in-memory buffer / click-to-mark spans, TTS, AssemblyAI + whisper.cpp providers, JNativeHook hotkeys. Don't add scaffolding for these unless the current task calls for it.

## Commands

Gradle wrapper is **8.10.2** (required for Java 21 runtime support). Java toolchain is 21.

```bash
./gradlew compileJava           # compile only
./gradlew build                 # compile + test
./gradlew test                  # JUnit 5
./gradlew test --tests <FQCN>   # single test class
./gradlew run                   # launches the JavaFX UI (InterviewApp)
./gradlew packageApp            # native Windows .exe via jpackage (see below)
```

**`./gradlew run` launches the GUI**, not a CLI — `application.mainClass` is `org.example.ui.InterviewApp`. The `standardInput = System.in` wiring in `build.gradle.kts` is a leftover from the CLI era and is harmless to the UI.

`org.example.Main` is a **legacy Phase-1 CLI smoke test** (lists devices, records 10 s to `test-recording.wav`). It is no longer the `run` target — to exercise it, run the class from the IDE or temporarily point `mainClass` at it. Its main value now is `Main.DEFAULT_FORMAT`, the project-wide audio contract (see below).

### Native packaging (`packageApp`)

`packageApp` runs `jpackage` to build a self-contained `InterviewAssistant.exe` app-image (bundles a private JRE — no Java needed on the target machine) plus a Desktop shortcut, under `Desktop\Flocareer\InterviewAssistant`. It resolves the *real* Desktop via PowerShell so OneDrive folder redirection works. Requires a full JDK 14+ (not a JRE) so `jpackage.exe` is present. The app icon is generated at build time by the `generateIcon` task, which writes an `.ico` + classpath `icon.png` using **only `java.base`** (hand-rolled BMP/PNG encoders — no `java.awt`, so it works on headless/module-restricted builds). `processResources` depends on `generateIcon`, so the icon is always fresh.

### CI and branch flow

`.github/workflows/ci.yml` runs `./gradlew build` (compile + JUnit) on push/PR — treat a clean `./gradlew build` as the required pre-PR gate. Work on descriptive task branches off `master`, fill out `.github/pull_request_template.md`, and see `docs/sdlc/development-flow.md` for the full flow. `.pipeline/` is scratch space for the `/ship` subagent pipeline (git-ignored except its `README.md`) — not part of the app.

## Architecture

### Dual-channel audio by OS routing, not diarization

The streams are kept physically separate at the OS level — we do **not** run speaker diarization. The user picks which mixer is "candidate" from the OS-discovered list. Virtual cables provide the candidate stream:

- Windows: VB-Audio Virtual Cable / VoiceMeeter (the UI auto-selects a device named "cable output")
- Linux: PulseAudio/PipeWire monitor source or `module-loopback`
- macOS: BlackHole

`AudioDevices.listCaptureDevices()` enumerates anything with a `TargetDataLine`; the user-visible boundary is `AudioDeviceInfo` (record carrying `Mixer.Info`). It intentionally does **not** deduplicate — on Linux/PipeWire the same physical device appears under multiple ALSA PCM names, and the user picks whichever entry routes the desired stream.

### Canonical audio format

`Main.DEFAULT_FORMAT` — **PCM signed, 16 kHz, 16-bit, mono, little-endian** — is the project-wide contract. Picked because Groq Whisper, AssemblyAI, Vosk and whisper.cpp all accept it natively. Don't change it lightly: every downstream component (STT provider, WAV writer, resampler, silence detection) assumes this shape.

### Capture pipeline with transparent resampling

`AudioCapture` opens a `TargetDataLine` and runs the read loop on a **named virtual thread**, dispatching ~100 ms chunks to a `Consumer<byte[]>` — the seam where downstream consumers plug in.

The non-obvious part: **the constructor `format` is the *output* format the consumer receives, not necessarily what the device opens.** Virtual cables (VB-Audio) typically only expose 44.1/48 kHz **stereo**, not 16 kHz mono. So `start()` probes candidate formats by *actually opening* the line (Windows/DirectSound's `isLineSupported` lies), records the `nativeFormat` it got, and — if that differs from the requested format — installs an `AudioResampler` that transparently down-mixes to mono and linearly resamples to 16 kHz. The consumer always sees the canonical format regardless of what the hardware gave us.

`AudioResampler` is **stateful**: it carries fractional phase across chunk boundaries, so `convert()` must be called in arrival order and is not thread-safe (it lives on the single capture thread, so that's fine). It only handles 16-bit LE PCM.

`stop()` closes the line to unblock the in-flight `read()`, then joins the reader with a 2-second timeout. Each audio channel gets its own `AudioCapture` + own STT provider on its own virtual thread — the fan-out pattern the rest of the app extends.

### STT provider abstraction

`SpeechToTextProvider` (in `org.example.stt`) is the vendor swap-point:

```java
interface SpeechToTextProvider extends AutoCloseable {
    void start(AudioFormat format, Consumer<TranscriptEvent> onResult) throws Exception;
    void sendAudioChunk(byte[] pcmData);   // must not block more than a few ms
    void stop();                            // idempotent; close() delegates here
}
```

`TranscriptEvent` (record) carries: text, partial-vs-final flag, confidence (-1 if unavailable), channel id.

There are **three** implementations, chosen at runtime via `SttEngine` (the "Transcrição" dropdown); `InterviewApp.createSttProvider(...)` is the factory that validates the credential/model and builds one:

**Batch providers (`BatchWindowSttProvider` base).** Both `GroqWhisperProvider` and `GeminiSttProvider` wrap a *batch* file API (POST a clip, get one transcript) behind the streaming interface, so the base handles the shared machinery: buffering ~N s windows (`sendAudioChunk` only appends under a short lock, never blocks on I/O), silence gating, and POSTing each window on a dedicated flush virtual thread. Subclasses implement only `transcribe(byte[])` (the provider HTTP call) plus `onStart`/`onStop` resource hooks. Consequences that downstream code must respect:

- **Only final events, no partials/interim.** The UI's partial label just stays empty.
- **Silence gating:** windows below a peak-amplitude floor (`SILENCE_PEAK`) or shorter than `MIN_BYTES` are dropped without a request — Whisper hallucinates phrases like "Thank you." on pure silence, and every POST spends the free tier's daily request budget.
- The PCM window is wrapped in an in-memory WAV via `WavFileWriter.wavHeader(...)` (the base's `toWav`) and sent to the provider.
- Groq uses a ~5 s window and `multipart/form-data`; Gemini uses a longer ~15 s window (its free tier is limited by *requests/day*, so fewer larger clips stretch the budget) and its native `generateContent` API with the WAV as base64 `inline_data`.

Provider pure helpers are package-private static so unit tests exercise them without a network call — Groq: `buildMultipartBody` / `extractText` / `friendlyError`; Gemini: `buildRequestJson` / `extractText` / `friendlyError`; shared `isSilent` lives on the base. Follow that pattern when adding provider logic.

**Streaming provider (`VoskSttProvider`).** Fully offline — a local model, no key, no quota, no network — so it never rate-limits and never hallucinates caption phrases. It is a true streaming recognizer: PCM is fed straight to a Vosk `Recognizer` and finalized at each end-of-utterance. To honor the final-only contract it emits one final `TranscriptEvent` per utterance and suppresses interim partials. Models are heavy (hundreds of MB) so they're cached statically by directory and shared across sessions; the `Model` is never closed on `stop()` (only the per-session `Recognizer` is). The model must be 16 kHz mono (= `Main.DEFAULT_FORMAT`). The UI's "Baixar modelo (EN)" button downloads + unzips the small English model (`unzip` has a Zip-Slip guard); "Modelo Vosk" can also point at a manually downloaded (larger, more accurate) model.

### LLM evaluation and the prompt↔parser coupling

**Provider-agnostic transport.** `GroqClient.call(...)` takes an `LlmProvider` (Groq / Gemini / Cerebras) and reads `provider.endpoint()` + `provider.defaultModel()`; the request/response body is identical across all three because each exposes the OpenAI `/chat/completions` route with `Authorization: Bearer <key>`. Adding another OpenAI-compatible backend is a one-line addition to the `LlmProvider` enum. The class name is legacy (`GroqClient`) — it is no longer Groq-specific. `QuestionPanel.analyze()` passes the provider chosen in the "Análise" dropdown and the matching key (`llmKeyFor`).

**The "thinking" gotcha (learned from live testing).** Gemini 2.5 Flash and GPT-OSS are hybrid *reasoning* models: by default they spend the output-token budget on internal thinking and truncate the visible answer **before** the trailing `RATING:` / `FOLLOW-UP QUESTIONS:` sections the parsers depend on — so the stars and follow-up radios silently vanish. Each `LlmProvider` therefore declares a `reasoningEffort()` (sent as the OpenAI-compatible `reasoning_effort`: `"none"` fully disables Gemini's thinking, `"low"` minimizes GPT-OSS's; `null` for non-thinking Llama 3.3 omits the field) plus a per-provider `maxTokens()`. **Also note model IDs rot:** Cerebras retired all its free-tier Qwen models in 2026 (`qwen-3-32b` → HTTP 404), so the Cerebras slot now uses `gpt-oss-120b`; if a default model 404s, update the enum.

`GroqClient` has **two evaluation entry points**, both returning free-form text whose format is a **contract with the `AnalysisResult` parser**:

- `evaluateAnswer(...)` (prompt: `buildEvaluationPrompt`) — scores a single initial Q&A.
- `evaluateExchange(...)` (prompt: `buildExchangePrompt`) — scores the **whole multi-turn exchange**: the initial Q&A plus a `List<FollowUpTurn>` of confirmed follow-up rounds, rendered as labeled turns by `buildConversationBlock`. **This is what `QuestionPanel.analyze()` actually calls now** — it snapshots every round's answer on the FX thread and asks the model for exactly 3 *new* follow-ups. `evaluateAnswer` is retained (and kept byte-identical) but no longer on the UI path. The two prompts share the preamble helpers `maxStars` / `whoLine` / `jobSection` / `expectedSection` / `scaleGuide`.

Both prompts instruct the model to end with a `FOLLOW-UP QUESTIONS:` section and a final `RATING: n/max` line. `org.example.llm.AnalysisResult` (a `record(prose, score, max, followUps)`) owns that parse: a single `AnalysisResult.parse(rawReply, fallbackMax)` call replaces the four strip/parse helpers the UI used to chain by hand, and `AnalysisResult.stars()` renders the star bar + level band. `QuestionPanel.analyze()` just reads `.prose()` / `.stars()` / `.followUps()`. **Change either prompt's tail format and you must change `AnalysisResult`'s `RATING_PATTERN` / `FOLLOWUP_HEADER_PATTERN`**, or the star rating / follow-up radios silently break.

### JavaFX threading model

`InterviewApp` is a single `Application`. All UI state is confined to the **JavaFX Application Thread**; the one deliberately cross-thread field is `activeQuestion` (`volatile`), read from STT callbacks to route transcripts to the current `QuestionPanel`. Everything blocking — session start/stop, capture, STT flush, LLM calls, Vosk model download — runs on **named virtual threads** (`start-session`, `stop-session`, `groq-whisper-candidate` / `gemini-stt-candidate`, `answer-evaluate-qN`, `vosk-download`) and marshals results back with `Platform.runLater`. Note the Vosk model is loaded inside `provider.start()` on the `start-session` thread, so the multi-second first load never blocks the FX thread. Transcript appends preserve the user's caret/selection/scroll when they're mid-edit in the candidate column.

Routing is **two-level**: `activeQuestion` selects the panel, and within that panel `QuestionPanel.currentSink` (FX-thread only) selects *which* `TextArea` receives finals — the initial answer area, or, once the interviewer confirms a follow-up (`confirmFollowUp` → `addRound`), the newest follow-up round's answer area. Confirming a round makes the previous round's area read-only and repoints `currentSink`; the initial answer area is intentionally left editable.

### Session persistence (plain text, not JSON)

Despite the spec envisioning JSON, sessions are persisted as **plain `.txt`**. The block format — rendering *and* parsing — is owned by `org.example.session.SessionCodec` (pure, no JavaFX), so the UI only orchestrates: `persistSessionTxt` rewrites the file in full on every candidate final event and on question/round changes, calling `SessionCodec.renderQuestionBlock` per panel. Location: `~/Desktop/Flocareer/candidatos/yyyy/MM/dd/<Company>_<Candidate>.txt` (nested date folders sort chronologically; filenames are sanitized against Windows reserved names). The format is a sequence of `--- Pergunta N ---` blocks, each `<question>` / blank line / `<candidate answer>`, optionally followed by one `FOLLOW-UP n: <follow-up question>` / (optional `EXPECTED: <guide>`) / `<follow-up answer>` group per confirmed round. `onOpenSession` round-trips this back into read-only panels (`SessionCodec.parseSessionFile` → `parseSection`, which splits head Q&A from the `FOLLOW-UP n:` tail via `FOLLOWUP_LINE_PATTERN`; `addLoadedRound` restores each round read-only). **The format is backward-compatible: a section with zero follow-ups renders byte-identically to the old single-Q&A format**, and the legacy no-blank-line fallback still loads older files. "Descartar Sessão" deletes the file from disk; "Fechar Sessão" keeps it and clears the panels.

### Streaming WAV writes

`WavFileWriter` writes a 44-byte placeholder header on construction, appends PCM via `RandomAccessFile`, and patches the two RIFF/data size fields on `close()`. `AudioSystem.write` is unsuitable — it needs the full length up front, and interview sessions run an hour+. `write()`/`close()` are `synchronized`. The static `wavHeader(format, dataLen)` is factored out and reused by `BatchWindowSttProvider.toWav(...)` (Groq + Gemini STT) to build in-memory WAVs.

### Try-with-resources order (CLI only)

In the legacy `Main`, `WavFileWriter` is declared **before** `AudioCapture` so close-in-reverse-order stops capture (and joins the reader) **before** the header is patched — late chunks after the writer closes would silently drop. Preserve this ordering when composing similar resources.

## Dependencies

- `jackson-databind 2.17.2` — JSON for LLM/STT request/response bodies.
- `com.alphacephei:vosk:0.3.45` — offline STT engine. The jar bundles the JNI native libraries for Windows/macOS/Linux (loaded via JNA at runtime), so no manual native build is needed; only a downloaded model directory is required at runtime.
- `slf4j-api` + `logback-classic` — logging; config in `src/main/resources/logback.xml`.
- `ikonli-javafx` + `ikonli-materialdesign2-pack` — Material Design icons in the UI (`FontIcon` literals like `mdi2p-play`). The stylesheet is `src/main/resources/styles/app.css`, loaded via `/styles/app.css`.
- JUnit 5 — tests now cover audio (`AudioDevicesTest`, `AudioResamplerTest`, `WavFileWriterTest`), STT (`GroqWhisperProviderTest`, `GeminiSttProviderTest`, `TranscriptEventTest`), LLM (`GroqClientTest` — prompt builders; `LlmProviderTest` — provider registry; `AnalysisResultTest` — the `parse` contract: rating, star bands, prose-stripping, follow-up header leniency), session (`SessionCodecTest` — the `renderQuestionBlock`↔`parseSection` round-trip), and UI (`AppStylesheetTest`, `IkonliFontIconTest`). Prefer extracting pure package-private/public static helpers into their own class (`SessionCodec`, `AnalysisResult`) so logic is testable without audio hardware, network, or the JavaFX toolkit. This is the pattern the P1 refactor followed to shrink the `InterviewApp` god class.

HTTP uses the JDK's `java.net.http.HttpClient` — no third-party HTTP dependency. Vosk is the only bundled native dependency.

## Conventions

- `org.example.audio` owns capture/IO primitives; `org.example.stt` owns transcription; `org.example.llm` owns LLM calls; `org.example.ui` owns JavaFX. Don't mix these concerns.
- Use `Thread.ofVirtual().name(...).start(...)` for I/O loops — named threads make logs and dumps readable.
- API keys are never logged or written to disk. External API error messages are truncated/sanitized before display (`sanitizeErrorMessage`, `friendlyError`, `translateGroqError`), and HTTP status codes are translated into actionable Portuguese messages for the UI.
- The tool must not automate or interact with any third-party web platform. It only captures audio at the OS level and calls Groq's HTTP API. Don't propose browser automation, page scraping, or DOM injection.
