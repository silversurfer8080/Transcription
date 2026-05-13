package org.example.ui;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.Main;
import org.example.audio.AudioCapture;
import org.example.audio.AudioDeviceInfo;
import org.example.audio.AudioDevices;
import org.example.audio.WavFileWriter;
import org.example.stt.DeepgramStreamingProvider;
import org.example.stt.TranscriptEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application window — three tabs:
 * <ol>
 *   <li><b>Interview</b> — dual-channel capture, per-question panels, WAV saving.</li>
 *   <li><b>Pronunciação</b> — select a recorded question and get a pronunciation
 *       critique from Claude.</li>
 *   <li><b>Avaliação</b> — paste question, expected answer, and candidate transcript
 *       to generate a structured evaluation via Claude.</li>
 * </ol>
 */
public class InterviewApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(InterviewApp.class);
    private static final DateTimeFormatter DATE_FOLDER_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    // ---- session state (FX thread only, except activeQuestion which is volatile) ----
    private AudioCapture micCapture;
    private AudioCapture candidateCapture;
    private DeepgramStreamingProvider micProvider;
    private DeepgramStreamingProvider candidateProvider;
    private boolean sessionRunning = false;
    private int questionCounter = 0;
    private BufferedWriter sessionTxtWriter;
    private volatile QuestionPanel activeQuestion = null;

    // Shared with PronunciationTab via ObservableList — the combo box updates automatically
    private final ObservableList<QuestionRef> sessionQuestions = FXCollections.observableArrayList();

    // ---- UI controls (interview tab) ----
    private PasswordField apiKeyField;
    private TextField companyField;
    private TextField candidateField;
    private ComboBox<AudioDeviceInfo> micCombo;
    private ComboBox<AudioDeviceInfo> candidateCombo;
    private Button sessionBtn;
    private Circle statusDot;
    private Button newQuestionBtn;
    private VBox questionsBox;
    private ScrollPane questionsScroll;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Interview Assistant");
        try (var is = InterviewApp.class.getResourceAsStream("/icon.png")) {
            if (is != null) stage.getIcons().add(new Image(is));
        } catch (Exception ignored) {}

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab interviewTab     = new Tab("Interview",    buildInterviewTabContent());
        Tab pronunciationTab = new Tab("Pronunciação", new PronunciationTab(sessionQuestions).buildContent());
        Tab evaluationTab    = new Tab("Avaliação",    new EvaluationTab().buildContent());

        tabPane.getTabs().addAll(interviewTab, pronunciationTab, evaluationTab);

        stage.setScene(new Scene(tabPane, 820, 700));
        stage.setAlwaysOnTop(false);
        stage.setOnCloseRequest(e -> onWindowClose());
        stage.show();
    }

    private Node buildInterviewTabContent() {
        // ── Row 1: API key ──────────────────────────────────────────────────
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("Deepgram API key");
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        String envKey = System.getenv("DEEPGRAM_API_KEY");
        if (envKey != null && !envKey.isBlank()) apiKeyField.setText(envKey);

        HBox row1 = new HBox(8, new Label("API Key:"), apiKeyField);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── Row 2: empresa + candidato ──────────────────────────────────────
        companyField   = new TextField();
        companyField.setPromptText("Nome da empresa");
        HBox.setHgrow(companyField, Priority.ALWAYS);

        candidateField = new TextField();
        candidateField.setPromptText("Nome do candidato");
        HBox.setHgrow(candidateField, Priority.ALWAYS);

        HBox row2 = new HBox(8,
                new Label("Empresa:"), companyField,
                new Label("Candidato:"), candidateField);
        row2.setAlignment(Pos.CENTER_LEFT);

        // ── Row 3: device selectors + session button ─────────────────────────
        List<AudioDeviceInfo> devices = AudioDevices.listCaptureDevices();

        micCombo = buildDeviceCombo(devices);
        candidateCombo = buildDeviceCombo(devices);

        statusDot = new Circle(7, Color.LIGHTGRAY);
        statusDot.setStroke(Color.GRAY);
        statusDot.setStrokeWidth(1);

        sessionBtn = new Button("▶  Start Session");
        sessionBtn.setMinWidth(140);
        sessionBtn.setOnAction(e -> onSessionToggle());

        HBox row3 = new HBox(8,
                new Label("Mic:"), micCombo,
                new Label("Candidate:"), candidateCombo,
                sessionBtn, statusDot);
        row3.setAlignment(Pos.CENTER_LEFT);

        // ── Row 4: new question button ───────────────────────────────────────
        newQuestionBtn = new Button("▶  New Question");
        newQuestionBtn.setDisable(true);
        newQuestionBtn.setOnAction(e -> onNewQuestion());

        HBox row4 = new HBox(newQuestionBtn);
        row4.setAlignment(Pos.CENTER_LEFT);

        VBox controls = new VBox(8, row1, row2, row3, row4);
        controls.setPadding(new Insets(10));

        // ── Questions scroll area ────────────────────────────────────────────
        questionsBox = new VBox(8);
        questionsBox.setPadding(new Insets(8));

        questionsScroll = new ScrollPane(questionsBox);
        questionsScroll.setFitToWidth(true);
        questionsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(questionsScroll, Priority.ALWAYS);

        VBox root = new VBox(controls, new Separator(), questionsScroll);
        VBox.setVgrow(questionsScroll, Priority.ALWAYS);
        return root;
    }

    // ── Session lifecycle ──────────────────────────────────────────────────

    private void onSessionToggle() {
        if (sessionRunning) stopSession(); else startSession();
    }

    private void startSession() {
        String apiKey = apiKeyField.getText().trim();
        if (apiKey.isEmpty()) { showAlert("API Key ausente", "Informe a Deepgram API key."); return; }

        String company   = companyField.getText().trim();
        String candidate = candidateField.getText().trim();
        if (company.isEmpty() || candidate.isEmpty()) {
            showAlert("Campos obrigatórios", "Preencha o nome da Empresa e do Candidato.");
            return;
        }

        AudioDeviceInfo micDev  = micCombo.getValue();
        AudioDeviceInfo candDev = candidateCombo.getValue();
        if (micDev == null || candDev == null) { showAlert("Sem dispositivo", "Selecione os dois dispositivos."); return; }
        if (micDev.mixerInfo().getName().equals(candDev.mixerInfo().getName())) {
            showAlert("Mesmo dispositivo",
                    "Mic e Candidate estão no mesmo dispositivo.\n\n" +
                    "Selecione o VB-Audio Virtual Cable (ou outro cabo virtual) " +
                    "para o canal Candidate.");
            return;
        }

        try {
            sessionTxtWriter = openSessionTxt(company, candidate);
        } catch (IOException e) {
            log.error("Failed to create session TXT", e);
            showAlert("Erro de arquivo", "Não foi possível criar o arquivo de transcrição:\n" + e.getMessage());
            return;
        }

        questionCounter = 0;
        sessionQuestions.clear();
        setSessionControlsEnabled(false);
        sessionBtn.setText("Connecting…");
        statusDot.setFill(Color.YELLOW);

        Thread.ofVirtual().name("start-session").start(() -> {
            try {
                DeepgramStreamingProvider mProv = new DeepgramStreamingProvider(apiKey, "mic");
                DeepgramStreamingProvider cProv = new DeepgramStreamingProvider(apiKey, "candidate");
                mProv.start(Main.DEFAULT_FORMAT, this::onMicEvent);
                cProv.start(Main.DEFAULT_FORMAT, this::onCandidateEvent);

                AudioCapture mCap = new AudioCapture(micDev.mixerInfo(), Main.DEFAULT_FORMAT,
                        chunk -> {
                            mProv.sendAudioChunk(chunk);
                            QuestionPanel q = activeQuestion;
                            if (q != null) q.writeAudio(chunk);
                        });
                AudioCapture cCap = new AudioCapture(candDev.mixerInfo(), Main.DEFAULT_FORMAT,
                        cProv::sendAudioChunk);

                mCap.start();
                cCap.start();

                Platform.runLater(() -> {
                    micProvider = mProv;   candidateProvider = cProv;
                    micCapture  = mCap;    candidateCapture  = cCap;
                    sessionRunning = true;
                    sessionBtn.setText("⏹  Stop Session");
                    sessionBtn.setDisable(false);
                    statusDot.setFill(Color.RED);
                    newQuestionBtn.setDisable(false);
                    log.info("Session started — mic='{}' candidate='{}'",
                            micDev.name(), candDev.name());
                });

            } catch (Exception ex) {
                log.error("Failed to start session", ex);
                closeSessionTxt();
                Platform.runLater(() -> {
                    setSessionControlsEnabled(true);
                    sessionBtn.setText("▶  Start Session");
                    statusDot.setFill(Color.LIGHTGRAY);
                    showAlert("Falha na conexão", extractErrorDetail(ex));
                });
            }
        });
    }

    private void stopSession() {
        sessionRunning = false;
        sessionBtn.setText("Stopping…");
        sessionBtn.setDisable(true);
        newQuestionBtn.setDisable(true);

        if (activeQuestion != null) { activeQuestion.markStopped(); activeQuestion = null; }
        closeSessionTxt();

        AudioCapture mCap = micCapture;       AudioCapture cCap = candidateCapture;
        DeepgramStreamingProvider mProv = micProvider; DeepgramStreamingProvider cProv = candidateProvider;
        micCapture = null; candidateCapture = null; micProvider = null; candidateProvider = null;

        Thread.ofVirtual().name("stop-session").start(() -> {
            if (mCap  != null) mCap.stop();
            if (cCap  != null) cCap.stop();
            if (mProv != null) mProv.stop();
            if (cProv != null) cProv.stop();
            Platform.runLater(() -> {
                setSessionControlsEnabled(true);
                sessionBtn.setText("▶  Start Session");
                statusDot.setFill(Color.LIGHTGRAY);
                log.info("Session stopped");
            });
        });
    }

    // ── Question lifecycle ─────────────────────────────────────────────────

    private void onNewQuestion() {
        if (activeQuestion != null) activeQuestion.markStopped();

        questionCounter++;

        if (sessionTxtWriter != null) {
            try {
                if (questionCounter > 1) sessionTxtWriter.newLine();
                sessionTxtWriter.write("--- Pergunta " + questionCounter + " ---");
                sessionTxtWriter.newLine();
                sessionTxtWriter.flush();
            } catch (IOException e) {
                log.error("Failed to write question header to TXT", e);
            }
        }

        QuestionPanel panel = new QuestionPanel(questionCounter);
        activeQuestion = panel;
        questionsBox.getChildren().add(panel.titledPane);

        // Register with the shared list so PronunciationTab's dropdown updates
        sessionQuestions.add(new QuestionRef(
                questionCounter,
                panel.wavPath,                          // may be null if WAV init failed
                () -> panel.questionFinal.toString()));

        Platform.runLater(() -> questionsScroll.setVvalue(1.0));
    }

    private void stopQuestion(QuestionPanel q) {
        if (activeQuestion == q) activeQuestion = null;
        q.markStopped();
    }

    // ── Transcript routing (WebSocket threads → QuestionPanel) ────────────

    private void onMicEvent(TranscriptEvent event) {
        QuestionPanel q = activeQuestion;
        if (q != null) q.onMicEvent(event);
    }

    private void onCandidateEvent(TranscriptEvent event) {
        QuestionPanel q = activeQuestion;
        if (q != null) q.onCandidateEvent(event);
    }

    // ── File helpers ───────────────────────────────────────────────────────

    private static BufferedWriter openSessionTxt(String company, String candidate) throws IOException {
        String dateDir  = LocalDate.now().format(DATE_FOLDER_FMT);
        String filename = sanitizeFilename(company) + "_" + sanitizeFilename(candidate) + ".txt";
        Path dir = Path.of(System.getProperty("user.home"), "Desktop", "Flocareer", "candidatos", dateDir);
        Files.createDirectories(dir);
        Path txt = dir.resolve(filename);
        log.info("Session TXT: {}", txt);
        return Files.newBufferedWriter(txt, StandardCharsets.UTF_8);
    }

    private void closeSessionTxt() {
        if (sessionTxtWriter == null) return;
        try { sessionTxtWriter.close(); } catch (IOException e) { log.error("Failed to close session TXT", e); }
        sessionTxtWriter = null;
    }

    // Replaces characters that Windows forbids in file names and blocks reserved
    // device names (NUL, CON, COM1-9, LPT1-9) that silently swallow writes.
    private static String sanitizeFilename(String name) {
        String s = name.trim()
                .replace(' ', '_')
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) s = "_" + s;
        if (s.isBlank() || s.matches("[. ]+")) s = "_unnamed_";
        return s;
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private static ComboBox<AudioDeviceInfo> buildDeviceCombo(List<AudioDeviceInfo> devices) {
        ComboBox<AudioDeviceInfo> combo = new ComboBox<>();
        combo.getItems().addAll(devices);
        combo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(combo, Priority.ALWAYS);
        if (!devices.isEmpty()) combo.getSelectionModel().selectFirst();
        return combo;
    }

    private void setSessionControlsEnabled(boolean enabled) {
        apiKeyField.setDisable(!enabled);
        companyField.setDisable(!enabled);
        candidateField.setDisable(!enabled);
        micCombo.setDisable(!enabled);
        candidateCombo.setDisable(!enabled);
        sessionBtn.setDisable(!enabled);
    }

    private static String sanitizeErrorMessage(String msg) {
        return msg.replaceAll("(?i)(token|key|auth(orization)?)[^\\s]*\\s*[=:]?\\s*\\S+", "[REDACTED]");
    }

    // Walks the cause chain to find the deepest non-null message, then translates
    // common HTTP status codes into actionable descriptions.
    private static String extractErrorDetail(Throwable ex) {
        String msg = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                msg = t.getMessage();
            }
        }
        if (msg == null) msg = ex.getClass().getSimpleName();
        if (msg.contains("401")) return "API key inválida ou expirada (401). Verifique a chave e tente novamente.";
        if (msg.contains("403")) return "Acesso negado pelo servidor STT (403). Verifique permissões da API key.";
        if (msg.contains("429")) return "Limite de requisições atingido (429). Aguarde alguns instantes e tente novamente.";
        return sanitizeErrorMessage(msg);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void onWindowClose() { if (sessionRunning) stopSession(); }

    @Override
    public void stop() { if (sessionRunning) stopSession(); }

    // ══════════════════════════════════════════════════════════════════════
    // QuestionPanel — one per interview question
    // ══════════════════════════════════════════════════════════════════════

    private class QuestionPanel {

        final int number;
        final StringBuilder questionFinal = new StringBuilder();
        final StringBuilder answerFinal   = new StringBuilder();
        final AtomicBoolean active = new AtomicBoolean(true);

        Path wavPath;           // set by initWavWriter(); exposed to QuestionRef
        WavFileWriter wavWriter;

        // UI (FX thread only)
        TitledPane titledPane;
        private TextArea questionArea;
        private TextArea answerArea;
        private Label    partialLabel;
        private Button   stopBtn;

        QuestionPanel(int number) {
            this.number = number;
            buildUI();
            initWavWriter();
        }

        // ── UI construction ──────────────────────────────────────────────

        private void buildUI() {
            Label qLabel = sectionLabel("MY QUESTION");
            questionArea = transcriptArea(3, "Your question will appear here as you speak…");

            Label aLabel = sectionLabel("CANDIDATE ANSWER");
            answerArea = transcriptArea(5, "Candidate answer will appear here…");

            partialLabel = new Label();
            partialLabel.setFont(Font.font(null, FontPosture.ITALIC, 12));
            partialLabel.setTextFill(Color.GRAY);
            partialLabel.setWrapText(true);
            partialLabel.setMaxWidth(Double.MAX_VALUE);

            Button copyBtn = new Button("📋  Copy Answer");
            copyBtn.setOnAction(e -> onCopyAnswer(copyBtn));

            stopBtn = new Button("⏹  Stop");
            stopBtn.setOnAction(e -> stopQuestion(this));

            HBox buttons = new HBox(8, copyBtn, stopBtn);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            buttons.setPadding(new Insets(4, 0, 0, 0));

            VBox content = new VBox(6,
                    qLabel, questionArea,
                    new Separator(),
                    aLabel, answerArea,
                    partialLabel,
                    buttons);
            content.setPadding(new Insets(10));

            titledPane = new TitledPane("Question " + number + "  🔴", content);
            titledPane.setExpanded(true);
            titledPane.setAnimated(true);
        }

        private Label sectionLabel(String text) {
            Label l = new Label(text);
            l.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #666;");
            return l;
        }

        private TextArea transcriptArea(int rows, String prompt) {
            TextArea ta = new TextArea();
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setPrefRowCount(rows);
            ta.setStyle("-fx-font-size: 13px;");
            ta.setPromptText(prompt);
            return ta;
        }

        // ── WAV file ────────────────────────────────────────────────────

        private void initWavWriter() {
            try {
                String dateDir = LocalDate.now().format(DATE_FOLDER_FMT);
                Path dir = Path.of(System.getProperty("user.home"), "Desktop", "Flocareer", "wav", dateDir);
                Files.createDirectories(dir);
                wavPath = dir.resolve("Q" + number + ".wav");
                wavWriter = new WavFileWriter(wavPath, Main.DEFAULT_FORMAT);
                log.info("WAV writer opened: {}", wavPath);
            } catch (IOException e) {
                log.error("Failed to open WAV for question {}", number, e);
            }
        }

        /** Called from the audio capture virtual thread — thread-safe via WavFileWriter sync. */
        void writeAudio(byte[] pcm) {
            if (!active.get() || wavWriter == null) return;
            try {
                wavWriter.write(pcm);
            } catch (IOException e) {
                log.error("WAV write error (question {})", number, e);
            }
        }

        // ── Transcript events (arrive on WebSocket thread) ───────────────

        void onMicEvent(TranscriptEvent event) {
            if (!event.isFinal()) return;
            Platform.runLater(() -> {
                questionFinal.append(event.text()).append(" ");
                questionArea.setText(questionFinal.toString());
                questionArea.setScrollTop(Double.MAX_VALUE);
                updateTitle();
            });
        }

        void onCandidateEvent(TranscriptEvent event) {
            Platform.runLater(() -> {
                if (event.isFinal()) {
                    answerFinal.append(event.text()).append(" ");
                    answerArea.setText(answerFinal.toString());
                    answerArea.setScrollTop(Double.MAX_VALUE);
                    partialLabel.setText("");
                    appendToSessionTxt(event.text());
                } else {
                    partialLabel.setText(event.text());
                }
            });
        }

        // ── Stop / collapse ──────────────────────────────────────────────

        void markStopped() {
            if (!active.compareAndSet(true, false)) return;
            try {
                if (wavWriter != null) wavWriter.close();
            } catch (IOException e) {
                log.error("Failed to close WAV for question {}", number, e);
            }
            Platform.runLater(() -> {
                stopBtn.setDisable(true);
                partialLabel.setText("");
                titledPane.setExpanded(false);
                updateTitle();
                log.info("Question {} stopped", number);
            });
        }

        // ── Helpers ─────────────────────────────────────────────────────

        private void appendToSessionTxt(String text) {
            BufferedWriter w = sessionTxtWriter;   // outer-class field, FX thread only
            if (w == null) return;
            try {
                w.write(text);
                w.newLine();
                w.flush();
            } catch (IOException e) {
                log.error("TXT write error (question {})", number, e);
            }
        }

        private void updateTitle() {
            String q = questionFinal.toString().trim();
            String preview = q.isEmpty() ? "" :
                    ": \"" + (q.length() > 55 ? q.substring(0, 55) + "…" : q) + "\"";
            String indicator = active.get() ? "  🔴" : "  ✓";
            titledPane.setText("Question " + number + preview + indicator);
        }

        private void onCopyAnswer(Button btn) {
            String answer = answerFinal.toString().trim();
            if (answer.isEmpty()) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(answer);
            Clipboard.getSystemClipboard().setContent(cc);
            btn.setText("✓  Copied!");
            PauseTransition p = new PauseTransition(Duration.seconds(1.5));
            p.setOnFinished(e -> btn.setText("📋  Copy Answer"));
            p.play();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
