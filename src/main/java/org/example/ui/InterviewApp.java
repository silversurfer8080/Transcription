package org.example.ui;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.Main;
import org.example.audio.AudioCapture;
import org.example.audio.AudioDeviceInfo;
import org.example.audio.AudioDevices;
import org.example.llm.GroqClient;
import org.example.llm.LlmProvider;
import org.example.stt.GeminiSttProvider;
import org.example.stt.GroqWhisperProvider;
import org.example.stt.SpeechToTextProvider;
import org.example.stt.SttEngine;
import org.example.stt.TranscriptEvent;
import org.example.stt.VoskSttProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application window — a single Interview tab: candidate-channel capture
 * and per-question panels (QUESTION / EXPECTED ANSWER / CANDIDATE ANSWER) with
 * an inline Groq evaluation and star rating.
 */
public class InterviewApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(InterviewApp.class);
    // Nested yyyy/MM/dd so date folders sort chronologically (year > month > day).
    private static final DateTimeFormatter DATE_FOLDER_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // Font size for the top form labels/fields — a couple px above the Modena default (13px)
    private static final String FORM_FONT_STYLE = "-fx-font-size: 15px;";
    private static final String STYLESHEET = "/styles/app.css";

    // ── Follow-up round UI state (FX-thread only) ──────────────────────────────

    /**
     * One confirmed follow-up round inside a QuestionPanel. Its three text areas live
     * in the three columns (below the initial Q/E/A, divided by a separator line):
     * the read-only question in QUESTION, the AI-generated guide in EXPECTED ANSWER,
     * and the candidate's spoken answer in CANDIDATE ANSWER.
     */
    private static final class FollowUpRound {
        final String   question;
        final TextArea questionView;   // read-only, QUESTION column
        final TextArea expectedArea;   // AI-generated reference points (editable), EXPECTED column
        final TextArea answerArea;     // candidate's follow-up answer (live sink), CANDIDATE column
        FollowUpRound(String question, TextArea questionView, TextArea expectedArea, TextArea answerArea) {
            this.question     = question;
            this.questionView = questionView;
            this.expectedArea = expectedArea;
            this.answerArea   = answerArea;
        }
    }

    // ── Persistence DTOs (package-private so same-package tests can reach them) ─

    /** A follow-up (question, AI-generated expected guide, candidate answer) read back from disk. */
    record FollowUp(String question, String expected, String answer) {}

    /** A question section parsed back from a saved session TXT. */
    record LoadedQuestion(String question, String answer, List<FollowUp> followUps) {}

    // Regex identifying FOLLOW-UP n: lines written by renderQuestionBlock.
    // Deliberately strict (hyphen, digits, colon) so legacy "FOLLOW UP QUESTION ->"
    // text inside transcripts is never mis-parsed as a follow-up marker.
    private static final Pattern FOLLOWUP_LINE_PATTERN =
            Pattern.compile("^\\s*FOLLOW-UP\\s+(\\d+):\\s?(.*)$");

    // Optional first line of a follow-up block carrying its AI-generated guide.
    private static final Pattern FOLLOWUP_EXPECTED_PATTERN =
            Pattern.compile("^\\s*EXPECTED:\\s?(.*)$");

    // ---- session state (FX thread only, except activeQuestion which is volatile) ----
    private AudioCapture candidateCapture;
    private SpeechToTextProvider candidateProvider;
    private boolean sessionRunning = false;
    private int questionCounter = 0;
    private Path sessionTxtPath;   // rewritten in full on each candidate final / stop
    private volatile QuestionPanel activeQuestion = null;
    private final List<QuestionPanel> questionPanels = new ArrayList<>();
    private Stage primaryStage;

    // Global font size for all transcript TextAreas — bound via styleProperty
    private final IntegerProperty fontSize = new SimpleIntegerProperty(14);

    // ---- UI controls (interview tab) ----
    // API keys — one per provider; a key is reused when the same provider is picked
    // for both STT and LLM (e.g. Gemini for transcription AND analysis).
    private PasswordField groqKeyField;      // Groq — Whisper STT and/or Llama LLM
    private PasswordField geminiKeyField;    // Gemini — STT and/or LLM
    private PasswordField cerebrasKeyField;  // Cerebras — LLM only (Qwen3)
    private ComboBox<SttEngine> sttEngineCombo;       // which transcription backend
    private ComboBox<LlmProvider> llmProviderCombo;   // which analysis backend
    private TextField voskModelField;        // local Vosk model directory (STT only)
    private HBox voskModelRow;               // shown only when Vosk is the STT engine
    private TextField companyField;
    private TextField candidateField;
    private TextField jobField;
    private ComboBox<String> sexCombo;   // Male/Female → he/him or she/her in the AI prompt
    private ComboBox<String> scaleCombo; // 5 or 10 → star-rating scale for the AI analysis
    private ComboBox<AudioDeviceInfo> candidateCombo;
    private Button sessionBtn;
    private Circle statusDot;
    private Button newQuestionBtn;
    private Button saveSessionBtn;
    private Button discardSessionBtn;
    private Button openSessionBtn;
    private VBox questionsBox;
    private ScrollPane questionsScroll;
    private TextArea jobDescArea;   // session-level full job description (collapsible top panel)

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("Interview Assistant");
        try (var is = InterviewApp.class.getResourceAsStream("/icon.png")) {
            if (is != null) stage.getIcons().add(new Image(is));
        } catch (Exception ignored) {}

        Region root = (Region) buildInterviewTabContent();
        Scene scene = new Scene(root, 820, 700);
        applyStylesheet(scene);
        stage.setScene(scene);
        stage.setAlwaysOnTop(false);
        stage.setOnCloseRequest(e -> onWindowClose());
        stage.show();
    }

    private Node buildInterviewTabContent() {
        // ── API keys (collapsible) — one field per provider, prefilled from env ──
        groqKeyField     = apiKeyField("Groq API key  (gsk_...)",   "GROQ_API_KEY");
        geminiKeyField   = apiKeyField("Gemini API key  (AIza...)", "GEMINI_API_KEY");
        cerebrasKeyField = apiKeyField("Cerebras API key  (csk-...)", "CEREBRAS_API_KEY");

        GridPane keysGrid = new GridPane();
        keysGrid.setHgap(8);
        keysGrid.setVgap(6);
        keysGrid.addRow(0, formLabel("Groq:"),     groqKeyField);
        keysGrid.addRow(1, formLabel("Gemini:"),   geminiKeyField);
        keysGrid.addRow(2, formLabel("Cerebras:"), cerebrasKeyField);
        GridPane.setHgrow(groqKeyField, Priority.ALWAYS);
        GridPane.setHgrow(geminiKeyField, Priority.ALWAYS);
        GridPane.setHgrow(cerebrasKeyField, Priority.ALWAYS);

        TitledPane keysPane = new TitledPane("Chaves de API (Groq / Gemini / Cerebras)", keysGrid);
        keysPane.setExpanded(false);
        keysPane.setAnimated(true);
        keysPane.setGraphic(icon("mdi2k-key-variant"));

        // ── Row 1: STT engine + LLM provider + power button ─────────────────────
        sttEngineCombo = new ComboBox<>(FXCollections.observableArrayList(SttEngine.values()));
        sttEngineCombo.setValue(SttEngine.VOSK);   // the unlimited, offline default
        sttEngineCombo.setStyle(FORM_FONT_STYLE);
        sttEngineCombo.setOnAction(e -> updateVoskRowVisibility());

        llmProviderCombo = new ComboBox<>(FXCollections.observableArrayList(LlmProvider.values()));
        llmProviderCombo.setValue(LlmProvider.GEMINI);   // most generous free tier
        llmProviderCombo.setStyle(FORM_FONT_STYLE);

        Button powerBtn = new Button("");
        powerBtn.setGraphic(icon("mdi2p-power"));
        powerBtn.getStyleClass().addAll("btn-icon", "btn-danger");
        powerBtn.setTooltip(new Tooltip("Fechar aplicação"));
        powerBtn.setOnAction(e -> { if (sessionRunning) stopSession(); Platform.exit(); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row1 = new HBox(8,
                formLabel("Transcrição:"), sttEngineCombo,
                formLabel("Análise:"), llmProviderCombo,
                spacer, powerBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── Vosk model row (shown only when Vosk is the STT engine) ─────────────
        voskModelField = new TextField(defaultVoskModelDir());
        voskModelField.setPromptText("Pasta do modelo Vosk (ex.: …\\vosk-model-small-en-us-0.15)");
        voskModelField.setStyle(FORM_FONT_STYLE);
        voskModelField.getStyleClass().add("field");
        HBox.setHgrow(voskModelField, Priority.ALWAYS);

        Button voskBrowseBtn = new Button("");
        voskBrowseBtn.setGraphic(icon("mdi2f-folder-open"));
        voskBrowseBtn.getStyleClass().add("btn-icon");
        voskBrowseBtn.setTooltip(new Tooltip("Escolher pasta do modelo"));
        voskBrowseBtn.setOnAction(e -> onBrowseVoskModel());

        Button voskDownloadBtn = new Button("Baixar modelo (EN)");
        voskDownloadBtn.setGraphic(icon("mdi2d-download"));
        voskDownloadBtn.setGraphicTextGap(6);
        voskDownloadBtn.getStyleClass().add("btn-secondary");
        voskDownloadBtn.setTooltip(new Tooltip("Baixa o modelo de inglês pequeno (~40 MB) automaticamente"));
        voskDownloadBtn.setOnAction(e -> onDownloadVoskModel(voskDownloadBtn));

        voskModelRow = new HBox(8, formLabel("Modelo Vosk:"), voskModelField, voskBrowseBtn, voskDownloadBtn);
        voskModelRow.setAlignment(Pos.CENTER_LEFT);
        updateVoskRowVisibility();

        // ── Row 2: name + job + company + sex + stars ────────────────────────
        candidateField = new TextField();
        candidateField.setPromptText("Candidate name");
        candidateField.setStyle(FORM_FONT_STYLE);
        candidateField.getStyleClass().add("field");
        candidateField.setPrefWidth(240);

        jobField = new TextField();
        jobField.setPromptText("Job / role");
        jobField.setStyle(FORM_FONT_STYLE);
        jobField.getStyleClass().add("field");
        jobField.setPrefWidth(220);

        companyField   = new TextField();
        companyField.setPromptText("Company");
        companyField.setStyle(FORM_FONT_STYLE);
        companyField.getStyleClass().add("field");
        companyField.setPrefWidth(150);

        sexCombo = new ComboBox<>(FXCollections.observableArrayList("Male", "Female"));
        sexCombo.setPromptText("Sex");
        sexCombo.setStyle(FORM_FONT_STYLE);

        scaleCombo = new ComboBox<>(FXCollections.observableArrayList("5", "10"));
        scaleCombo.setValue("5");   // most interviews use a 1–5 scale
        scaleCombo.setStyle(FORM_FONT_STYLE);

        HBox row2 = new HBox(8,
                formLabel("Name:"), candidateField,
                formLabel("Job:"), jobField,
                formLabel("Company:"), companyField,
                formLabel("Sex:"), sexCombo,
                formLabel("Stars:"), scaleCombo);
        row2.setAlignment(Pos.CENTER_LEFT);

        // ── Row 3: candidate device + session button ─────────────────────────
        List<AudioDeviceInfo> devices = AudioDevices.listCaptureDevices();

        candidateCombo = buildDeviceCombo(devices);

        // Pre-select the VB-Audio Cable for the candidate channel
        autoSelectByKeyword(candidateCombo, devices, "cable output");

        statusDot = new Circle(7, Color.LIGHTGRAY);
        statusDot.setStroke(Color.GRAY);
        statusDot.setStrokeWidth(1);

        sessionBtn = new Button("Start Session");
        sessionBtn.setGraphic(icon("mdi2p-play"));
        sessionBtn.setGraphicTextGap(6);
        sessionBtn.getStyleClass().add("btn-primary");
        sessionBtn.setMinWidth(140);
        sessionBtn.setOnAction(e -> onSessionToggle());

        HBox row3 = new HBox(8,
                formLabel("Candidate:"), candidateCombo,
                sessionBtn, statusDot);
        row3.setAlignment(Pos.CENTER_LEFT);

        // ── Row 4: question controls + font size ─────────────────────────────
        newQuestionBtn = new Button("New Question");
        newQuestionBtn.setGraphic(icon("mdi2p-plus"));
        newQuestionBtn.setGraphicTextGap(6);
        newQuestionBtn.getStyleClass().add("btn-secondary");
        newQuestionBtn.setDisable(true);
        newQuestionBtn.setOnAction(e -> onNewQuestion());

        saveSessionBtn = new Button("Fechar Sessão");
        saveSessionBtn.setGraphic(icon("mdi2b-broom"));
        saveSessionBtn.setGraphicTextGap(6);
        saveSessionBtn.getStyleClass().add("btn-secondary");
        saveSessionBtn.setDisable(true);
        saveSessionBtn.setOnAction(e -> onSaveSession());

        discardSessionBtn = new Button("Descartar Sessão");
        discardSessionBtn.setGraphic(icon("mdi2t-trash-can-outline"));
        discardSessionBtn.setGraphicTextGap(6);
        discardSessionBtn.getStyleClass().add("btn-danger");
        discardSessionBtn.setDisable(true);
        discardSessionBtn.setOnAction(e -> onDiscardSession());

        openSessionBtn = new Button("Abrir Sessão");
        openSessionBtn.setGraphic(icon("mdi2f-folder-open"));
        openSessionBtn.setGraphicTextGap(6);
        openSessionBtn.getStyleClass().add("btn-secondary");
        openSessionBtn.setOnAction(e -> onOpenSession());

        Label fontLabel = new Label();
        fontLabel.textProperty().bind(Bindings.concat("Fonte: ", fontSize, "px"));
        fontLabel.getStyleClass().add("muted-label");
        Button fontDecBtn = new Button("A−");
        fontDecBtn.getStyleClass().add("btn-ghost");
        fontDecBtn.setOnAction(e -> { if (fontSize.get() > 8) fontSize.set(fontSize.get() - 1); });
        Button fontIncBtn = new Button("A+");
        fontIncBtn.getStyleClass().add("btn-ghost");
        fontIncBtn.setOnAction(e -> { if (fontSize.get() < 36) fontSize.set(fontSize.get() + 1); });

        Separator vertSep = new Separator(Orientation.VERTICAL);
        HBox row4 = new HBox(8, newQuestionBtn, saveSessionBtn, discardSessionBtn, openSessionBtn,
                vertSep, fontLabel, fontDecBtn, fontIncBtn);
        row4.setAlignment(Pos.CENTER_LEFT);

        VBox controls = new VBox(8, keysPane, row1, voskModelRow, row2, row3, row4);
        controls.setPadding(new Insets(10));

        // ── Questions scroll area ────────────────────────────────────────────
        questionsBox = new VBox(8);
        questionsBox.setPadding(new Insets(8));

        questionsScroll = new ScrollPane(questionsBox);
        questionsScroll.setFitToWidth(true);
        questionsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(questionsScroll, Priority.ALWAYS);

        // ── Job description collapsible panel (top of window) ───────────────
        jobDescArea = new TextArea();
        jobDescArea.setPromptText("Cole aqui a descrição da vaga (Job Description)…");
        jobDescArea.setWrapText(true);
        jobDescArea.setPrefRowCount(4);
        jobDescArea.setStyle(FORM_FONT_STYLE);

        TitledPane jobDescPane = new TitledPane("Descrição da Vaga (Job Description)", jobDescArea);
        jobDescPane.setExpanded(false);
        jobDescPane.setAnimated(true);
        jobDescPane.setGraphic(icon("mdi2c-comment-question-outline"));

        VBox root = new VBox(jobDescPane, controls, new Separator(), questionsScroll);
        VBox.setMargin(jobDescPane, new Insets(10, 10, 0, 10));
        VBox.setVgrow(questionsScroll, Priority.ALWAYS);
        return root;
    }

    // ── Provider selection helpers ─────────────────────────────────────────

    /** Builds a password field for an API key, prefilled from the given env var. */
    private PasswordField apiKeyField(String prompt, String envVar) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        f.setStyle(FORM_FONT_STYLE);
        f.getStyleClass().add("field");
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) f.setText(env);
        return f;
    }

    /** Default folder the "Baixar modelo" button targets / where a model is looked for. */
    private static String defaultVoskModelDir() {
        return Path.of(System.getProperty("user.home"),
                "Flocareer", "models", "vosk-model-small-en-us-0.15").toString();
    }

    /** The Vosk model row is only relevant when Vosk is the selected STT engine. */
    private void updateVoskRowVisibility() {
        boolean vosk = sttEngineCombo.getValue() == SttEngine.VOSK;
        if (voskModelRow != null) {
            voskModelRow.setVisible(vosk);
            voskModelRow.setManaged(vosk);   // collapse layout space when hidden
        }
    }

    private void onBrowseVoskModel() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Selecione a pasta do modelo Vosk");
        java.io.File cur = new java.io.File(voskModelField.getText().trim());
        if (cur.isDirectory())                              dc.setInitialDirectory(cur);
        else if (cur.getParentFile() != null && cur.getParentFile().isDirectory())
                                                            dc.setInitialDirectory(cur.getParentFile());
        java.io.File chosen = dc.showDialog(primaryStage);
        if (chosen != null) voskModelField.setText(chosen.getAbsolutePath());
    }

    /**
     * Downloads and unzips the small English Vosk model (~40 MB) into the default
     * models folder, then points the field at it. Runs off the FX thread; the button
     * shows progress. For higher accuracy the user can instead download the large
     * model manually and browse to it.
     */
    private void onDownloadVoskModel(Button btn) {
        final String modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
        final Path targetDir  = Path.of(defaultVoskModelDir());
        final Path modelsDir  = targetDir.getParent();      // …/Flocareer/models
        final String original = btn.getText();
        btn.setDisable(true);
        btn.setText("Baixando…");

        Thread.ofVirtual().name("vosk-download").start(() -> {
            try {
                if (isVoskModel(targetDir)) {
                    finishDownload(btn, original, targetDir, "Modelo já existe",
                            "O modelo já está instalado em:\n" + targetDir);
                    return;
                }
                Files.createDirectories(modelsDir);
                Path zip = Files.createTempFile("vosk-model", ".zip");
                try (HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)   // model host may redirect
                        .build()) {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(modelUrl)).GET().build();
                    HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(zip));
                    if (resp.statusCode() != 200)
                        throw new IOException("HTTP " + resp.statusCode() + " ao baixar o modelo");
                }
                Platform.runLater(() -> btn.setText("Extraindo…"));
                unzip(zip, modelsDir);
                Files.deleteIfExists(zip);
                if (!isVoskModel(targetDir))
                    throw new IOException("Zip extraído, mas o modelo não foi encontrado em " + targetDir);
                finishDownload(btn, original, targetDir, "Modelo baixado",
                        "Modelo de inglês instalado em:\n" + targetDir);
            } catch (Exception ex) {
                log.error("Vosk model download failed", ex);
                Platform.runLater(() -> {
                    btn.setText(original);
                    btn.setDisable(false);
                    showAlert("Falha no download",
                            "Não foi possível baixar o modelo Vosk:\n" + ex.getMessage()
                            + "\n\nBaixe manualmente em alphacephei.com/vosk/models e aponte a pasta"
                            + " no campo \"Modelo Vosk\".");
                });
            }
        });
    }

    private void finishDownload(Button btn, String original, Path dir, String title, String msg) {
        Platform.runLater(() -> {
            voskModelField.setText(dir.toString());
            btn.setText(original);
            btn.setDisable(false);
            showInfo(title, msg);
        });
    }

    /** A Vosk model directory always contains the acoustic-model ("am") + config folders. */
    private static boolean isVoskModel(Path dir) {
        return Files.isDirectory(dir)
                && (Files.isDirectory(dir.resolve("am")) || Files.isDirectory(dir.resolve("conf")));
    }

    /** Extracts a zip into destDir, guarding against Zip-Slip path traversal. */
    private static void unzip(Path zip, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = destDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(destDir))
                    throw new IOException("Entrada de zip inválida: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Builds the STT provider chosen in the "Transcrição" dropdown, validating its
     * credential/model first. Returns null (after showing an alert) when the required
     * key or model is missing, so callers can just {@code return}.
     */
    private SpeechToTextProvider createSttProvider(String channelId) {
        SttEngine engine = sttEngineCombo.getValue();
        return switch (engine) {
            case VOSK -> {
                String path = voskModelField.getText().trim();
                if (path.isEmpty() || !isVoskModel(Path.of(path))) {
                    showAlert("Modelo Vosk ausente",
                            "Selecione a pasta de um modelo Vosk válido, ou clique em"
                            + " \"Baixar modelo (EN)\" para instalar o modelo de inglês.");
                    yield null;
                }
                yield new VoskSttProvider(path, channelId);
            }
            case GROQ_WHISPER -> {
                String key = groqKeyField.getText().trim();
                if (key.isEmpty()) {
                    showAlert("Chave Groq ausente", "Informe a Groq API key para usar o Whisper.");
                    yield null;
                }
                yield new GroqWhisperProvider(key, channelId);
            }
            case GEMINI -> {
                String key = geminiKeyField.getText().trim();
                if (key.isEmpty()) {
                    showAlert("Chave Gemini ausente", "Informe a Gemini API key para a transcrição.");
                    yield null;
                }
                yield new GeminiSttProvider(key, channelId);
            }
        };
    }

    /** Returns the API key filled for the given LLM provider, trimmed (may be empty). */
    private String llmKeyFor(LlmProvider provider) {
        return switch (provider) {
            case GROQ     -> groqKeyField.getText().trim();
            case GEMINI   -> geminiKeyField.getText().trim();
            case CEREBRAS -> cerebrasKeyField.getText().trim();
        };
    }

    // ── Session lifecycle ──────────────────────────────────────────────────

    private void onSessionToggle() {
        if (sessionRunning) stopSession(); else startSession();
    }

    private void startSession() {
        // Build (and validate) the chosen transcription backend up front, on the FX
        // thread — construction is cheap; the expensive Vosk model load happens later
        // in start(), off-thread. A null return means validation already alerted.
        SpeechToTextProvider cProv = createSttProvider("candidate");
        if (cProv == null) return;

        String company   = companyField.getText().trim();
        String candidate = candidateField.getText().trim();
        if (company.isEmpty() || candidate.isEmpty()) {
            showAlert("Campos obrigatórios", "Preencha o nome da Empresa e do Candidato.");
            return;
        }

        AudioDeviceInfo candDev = candidateCombo.getValue();
        if (candDev == null) { showAlert("Sem dispositivo", "Selecione o dispositivo do candidato."); return; }

        try {
            sessionTxtPath = openSessionTxt(company, candidate);
        } catch (IOException e) {
            log.error("Failed to create session TXT", e);
            showAlert("Erro de arquivo", "Não foi possível criar o arquivo de transcrição:\n" + e.getMessage());
            return;
        }

        // Clear any leftover panels from a previous session before starting fresh
        questionsBox.getChildren().clear();
        questionPanels.clear();
        questionCounter = 0;
        setSessionControlsEnabled(false);
        sessionBtn.setText("Connecting…");
        statusDot.setFill(Color.YELLOW);

        // Surface STT errors (bad key, rate limit, network, model load) on the status dot.
        cProv.setErrorListener(msg -> Platform.runLater(() -> {
            statusDot.setFill(Color.ORANGE);
            log.warn("STT: {}", msg);
        }));

        Thread.ofVirtual().name("start-session").start(() -> {
            try {
                // Only the candidate channel is captured and transcribed. The provider
                // is whichever was picked in the "Transcrição" dropdown (Vosk / Groq /
                // Gemini); Vosk loads its model here, off the FX thread.
                cProv.start(Main.DEFAULT_FORMAT, this::onCandidateEvent);

                AudioCapture cCap = new AudioCapture(candDev.mixerInfo(), Main.DEFAULT_FORMAT,
                        cProv::sendAudioChunk);

                cCap.start();

                Platform.runLater(() -> {
                    candidateProvider = cProv;
                    candidateCapture  = cCap;
                    sessionRunning = true;
                    sessionBtn.setText("Stop Session");
                    sessionBtn.setGraphic(icon("mdi2s-stop"));
                    sessionBtn.setDisable(false);
                    statusDot.setFill(Color.RED);
                    newQuestionBtn.setDisable(false);
                    discardSessionBtn.setDisable(false);   // available even with no questions yet
                    // Re-enable Continue on any already-stopped panels from a previous round
                    questionPanels.forEach(p -> { if (!p.active.get()) p.setContinueEnabled(true); });
                    log.info("Session started — candidate='{}'", candDev.name());
                });

            } catch (Exception ex) {
                log.error("Failed to start session", ex);
                closeSessionTxt();
                Platform.runLater(() -> {
                    setSessionControlsEnabled(true);
                    sessionBtn.setText("Start Session");
                    sessionBtn.setGraphic(icon("mdi2p-play"));
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
        saveSessionBtn.setDisable(true);

        if (activeQuestion != null) { activeQuestion.markStopped(); activeQuestion = null; }
        // Flush the latest content but KEEP sessionTxtPath so the file can still be
        // discarded after stopping (Stop does not null the path; Fechar/Descartar do).
        persistSessionTxt();

        // Disable Continue buttons while no session is running
        questionPanels.forEach(p -> p.setContinueEnabled(false));

        AudioCapture cCap = candidateCapture;
        SpeechToTextProvider cProv = candidateProvider;
        candidateCapture = null; candidateProvider = null;

        Thread.ofVirtual().name("stop-session").start(() -> {
            if (cCap  != null) cCap.stop();
            if (cProv != null) cProv.stop();
            Platform.runLater(() -> {
                setSessionControlsEnabled(true);
                sessionBtn.setText("Start Session");
                sessionBtn.setGraphic(icon("mdi2p-play"));
                statusDot.setFill(Color.LIGHTGRAY);
                // Keep save/clear and discard available so the user can review,
                // then explicitly keep (Fechar) or delete (Descartar) the session.
                boolean nothingToKeep = questionPanels.isEmpty();
                saveSessionBtn.setDisable(nothingToKeep);
                discardSessionBtn.setDisable(sessionTxtPath == null && nothingToKeep);
                log.info("Session stopped");
            });
        });
    }

    // ── Question lifecycle ─────────────────────────────────────────────────

    private void onNewQuestion() {
        if (activeQuestion != null) activeQuestion.markStopped();

        // Re-open session TXT when cleared by onSaveSession() and the user starts a new question
        if (sessionTxtPath == null && sessionRunning) {
            String company   = companyField.getText().trim();
            String candidate = candidateField.getText().trim();
            if (!company.isEmpty() && !candidate.isEmpty()) {
                try {
                    sessionTxtPath = openSessionTxt(company, candidate);
                } catch (IOException e) {
                    log.error("Failed to re-create session TXT", e);
                }
            }
        }

        questionCounter++;

        QuestionPanel panel = new QuestionPanel(questionCounter);
        activeQuestion = panel;
        questionPanels.add(panel);
        questionsBox.getChildren().add(panel.titledPane);

        persistSessionTxt();
        saveSessionBtn.setDisable(false);
        discardSessionBtn.setDisable(false);
        Platform.runLater(() -> questionsScroll.setVvalue(1.0));
    }

    private void stopQuestion(QuestionPanel q) {
        if (activeQuestion == q) activeQuestion = null;
        q.markStopped();
    }

    private void onSaveSession() {
        ButtonType btnClear  = new ButtonType("Limpar", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Fechar sessão");
        confirm.setHeaderText(null);
        confirm.setContentText(
                "Limpar todos os painéis e preparar para a próxima entrevista?\n\n" +
                "O arquivo de transcrição já foi salvo automaticamente em disco.");
        confirm.getButtonTypes().setAll(btnClear, btnCancel);
        var cssUrl1 = getClass().getResource(STYLESHEET);
        if (cssUrl1 != null) {
            confirm.getDialogPane().getStylesheets().add(cssUrl1.toExternalForm());
            confirm.getDialogPane().getStyleClass().add("app-dialog");
        }
        confirm.showAndWait().ifPresent(bt -> { if (bt == btnClear) doClearSession(); });
    }

    // Discards the current session: stops it, DELETES the saved TXT from disk, and
    // clears the panels. For when a candidate no-shows and there's nothing to keep.
    private void onDiscardSession() {
        ButtonType btnDiscard = new ButtonType("Descartar", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel  = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Descartar sessão");
        confirm.setHeaderText(null);
        confirm.setContentText(
                "Descartar a sessão atual e APAGAR o arquivo salvo em disco?\n\n" +
                "Use quando o candidato não compareceu. Esta ação não pode ser desfeita.");
        confirm.getButtonTypes().setAll(btnDiscard, btnCancel);
        var cssUrl2 = getClass().getResource(STYLESHEET);
        if (cssUrl2 != null) {
            confirm.getDialogPane().getStylesheets().add(cssUrl2.toExternalForm());
            confirm.getDialogPane().getStyleClass().add("app-dialog");
        }
        confirm.showAndWait().ifPresent(bt -> { if (bt == btnDiscard) doDiscardSession(); });
    }

    private void doDiscardSession() {
        Path toDelete = sessionTxtPath;
        if (sessionRunning) stopSession();   // stop capture/provider; keeps the path
        if (toDelete != null) {
            try {
                Files.deleteIfExists(toDelete);
                log.info("Discarded session TXT: {}", toDelete);
            } catch (IOException e) {
                log.error("Failed to delete session TXT: {}", toDelete, e);
            }
        }
        sessionTxtPath = null;   // prevent doClearSession() from re-persisting the file
        doClearSession();
    }

    private void doClearSession() {
        if (activeQuestion != null) {
            activeQuestion.markStopped();
            activeQuestion = null;
        }
        closeSessionTxt();
        questionsBox.getChildren().clear();
        questionPanels.clear();
        questionCounter = 0;
        saveSessionBtn.setDisable(true);
        discardSessionBtn.setDisable(true);
        companyField.setDisable(false);
        companyField.clear();
        candidateField.setDisable(false);
        candidateField.clear();
        jobField.setDisable(false);
        jobField.clear();
        jobDescArea.clear();
        sexCombo.setDisable(false);
        sexCombo.getSelectionModel().clearSelection();
        sexCombo.setValue(null);
        scaleCombo.setValue("5");
        log.info("Session cleared — ready for next candidate");
    }

    private void onOpenSession() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Abrir sessão salva");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Sessão de transcrição (*.txt)", "*.txt"));
        Path defaultDir = Path.of(System.getProperty("user.home"), "Desktop", "Flocareer", "candidatos");
        if (Files.exists(defaultDir)) chooser.setInitialDirectory(defaultDir.toFile());

        java.io.File file = chooser.showOpenDialog(primaryStage);
        if (file == null) return;

        List<LoadedQuestion> sections;
        try {
            sections = parseSessionFile(file.toPath());
        } catch (IOException e) {
            log.error("Failed to read session file", e);
            showAlert("Erro ao abrir", "Não foi possível ler o arquivo:\n" + e.getMessage());
            return;
        }

        if (sections.isEmpty()) {
            showAlert("Arquivo vazio", "Nenhuma pergunta encontrada no arquivo selecionado.");
            return;
        }

        doClearSession();

        // Derive company/candidate from filename (best-effort: Company_Candidate.txt)
        String filename = file.getName();
        if (filename.endsWith(".txt")) filename = filename.substring(0, filename.length() - 4);
        int sep = filename.indexOf('_');
        if (sep > 0) {
            companyField.setText(filename.substring(0, sep).replace('_', ' '));
            candidateField.setText(filename.substring(sep + 1).replace('_', ' '));
        }

        // Create one read-only panel per section; restore any follow-up rounds in order
        for (LoadedQuestion lq : sections) {
            questionCounter++;
            QuestionPanel panel = new QuestionPanel(questionCounter);
            panel.setInitialQuestion(lq.question());
            panel.setInitialAnswer(lq.answer());
            for (FollowUp fu : lq.followUps())
                panel.addLoadedRound(fu.question(), fu.expected(), fu.answer());
            panel.markStopped();
            questionPanels.add(panel);
            questionsBox.getChildren().add(panel.titledPane);
        }

        saveSessionBtn.setDisable(false);
        log.info("Loaded {} question(s) from {}", sections.size(), file.getName());
        Platform.runLater(() -> questionsScroll.setVvalue(0.0));
    }

    private static List<LoadedQuestion> parseSessionFile(Path path) throws IOException {
        List<LoadedQuestion> sections = new ArrayList<>();
        List<String> currentLines = null;

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.matches("--- Pergunta \\d+ ---")) {
                if (currentLines != null) sections.add(parseSection(currentLines));
                currentLines = new ArrayList<>();
            } else if (currentLines != null) {
                currentLines.add(line);   // keep blanks: they separate question from answer
            }
        }
        if (currentLines != null) sections.add(parseSection(currentLines));
        return sections;
    }

    // New format: <question lines> <blank> <answer lines>. Old format (no blank
    // line) is treated as answer-only so legacy files still load correctly.
    // Used by parseSection for the head portion (before any FOLLOW-UP lines).
    private static LoadedQuestion splitQuestionAnswer(List<String> lines) {
        int firstBlank = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) { firstBlank = i; break; }
        }
        if (firstBlank < 0) {
            return new LoadedQuestion("", joinNonBlank(lines), List.of());
        }
        return new LoadedQuestion(
                joinNonBlank(lines.subList(0, firstBlank)),
                joinNonBlank(lines.subList(firstBlank + 1, lines.size())),
                List.of());
    }

    /**
     * Parses a question section (lines between two {@code --- Pergunta N ---} headers)
     * into a {@link LoadedQuestion} with optional follow-up rounds.
     *
     * <p>Backward compatible: sections with no {@code FOLLOW-UP n:} markers produce a
     * {@code LoadedQuestion} with an empty follow-up list, identical in question/answer
     * content to the old single-Q/A behavior.
     */
    static LoadedQuestion parseSection(List<String> lines) {
        // Step 1: locate the first FOLLOW-UP n: line
        int fuStart = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (FOLLOWUP_LINE_PATTERN.matcher(lines.get(i)).matches()) {
                fuStart = i;
                break;
            }
        }

        // Step 2: parse head (initial question + answer) using existing logic
        LoadedQuestion qa = splitQuestionAnswer(lines.subList(0, fuStart));

        // Step 3: parse tail (follow-up rounds). Each block is:
        //   FOLLOW-UP n: <question>
        //   [EXPECTED: <guide>]     (optional; only the first content line)
        //   <answer lines>
        List<FollowUp> followUps = new ArrayList<>();
        List<String> tail = lines.subList(fuStart, lines.size());
        String currentFuQuestion = null;
        String currentFuExpected = "";
        List<String> currentFuAnswerLines = new ArrayList<>();
        for (String line : tail) {
            Matcher m = FOLLOWUP_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                if (currentFuQuestion != null) {
                    followUps.add(new FollowUp(currentFuQuestion, currentFuExpected,
                            joinNonBlank(currentFuAnswerLines)));
                }
                currentFuQuestion = m.group(2).trim();
                currentFuExpected = "";
                currentFuAnswerLines = new ArrayList<>();
            } else if (currentFuQuestion != null) {
                Matcher em = FOLLOWUP_EXPECTED_PATTERN.matcher(line);
                // Only the first content line of the block may be the EXPECTED guide,
                // so an "EXPECTED:" that appears later inside the answer is left intact.
                if (em.matches() && currentFuExpected.isEmpty() && currentFuAnswerLines.isEmpty()) {
                    currentFuExpected = em.group(1).trim();
                } else {
                    currentFuAnswerLines.add(line);
                }
            }
        }
        if (currentFuQuestion != null) {
            followUps.add(new FollowUp(currentFuQuestion, currentFuExpected,
                    joinNonBlank(currentFuAnswerLines)));
        }

        return new LoadedQuestion(qa.question(), qa.answer(), followUps);
    }

    private static String joinNonBlank(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (s.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s.trim());
        }
        return sb.toString();
    }

    // ── Transcript routing (STT threads → QuestionPanel) ──────────────────

    private void onCandidateEvent(TranscriptEvent event) {
        QuestionPanel q = activeQuestion;
        if (q != null) q.onCandidateEvent(event);
    }

    // ── File helpers ───────────────────────────────────────────────────────

    private static Path openSessionTxt(String company, String candidate) throws IOException {
        String dateDir  = LocalDate.now().format(DATE_FOLDER_FMT);
        String filename = sanitizeFilename(company) + "_" + sanitizeFilename(candidate) + ".txt";
        Path dir = Path.of(System.getProperty("user.home"), "Desktop", "Flocareer", "candidatos", dateDir);
        Files.createDirectories(dir);
        Path txt = dir.resolve(filename);
        log.info("Session TXT: {}", txt);
        return txt;
    }

    /**
     * Renders one question block as text for the session TXT file.
     *
     * <p>Format (line terminator = {@code System.lineSeparator()}):
     * <pre>
     * --- Pergunta N ---
     * &lt;question&gt;
     *
     * &lt;answer&gt;
     *
     * FOLLOW-UP 1: &lt;follow-up question&gt;
     * EXPECTED: &lt;AI-generated guide&gt;   (omitted when blank)
     * &lt;follow-up answer&gt;
     * </pre>
     *
     * With zero follow-ups the output is byte-identical to the previous format, and a
     * follow-up with a blank guide omits its {@code EXPECTED:} line — so files written
     * before the guide feature still round-trip and load correctly.
     */
    static String renderQuestionBlock(int number, String question, String answer,
                                      List<FollowUp> followUps) {
        StringBuilder sb = new StringBuilder();
        String ls = System.lineSeparator();
        sb.append("--- Pergunta ").append(number).append(" ---").append(ls);
        sb.append(question == null ? "" : question.trim()).append(ls);
        sb.append(ls);
        sb.append(answer == null ? "" : answer.trim()).append(ls);
        if (followUps != null) {
            for (int i = 0; i < followUps.size(); i++) {
                FollowUp fu = followUps.get(i);
                sb.append(ls);
                sb.append("FOLLOW-UP ").append(i + 1).append(": ")
                  .append(fu.question() == null ? "" : fu.question().trim()).append(ls);
                if (fu.expected() != null && !fu.expected().isBlank()) {
                    sb.append("EXPECTED: ").append(fu.expected().trim()).append(ls);
                }
                sb.append(fu.answer() == null ? "" : fu.answer().trim()).append(ls);
            }
        }
        return sb.toString();
    }

    // Rewrites the whole session TXT from the current panels.
    private void persistSessionTxt() {
        Path path = sessionTxtPath;
        if (path == null) return;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (QuestionPanel p : questionPanels) {
            if (!first) sb.append(System.lineSeparator());
            first = false;
            List<FollowUp> fus = new ArrayList<>();
            for (FollowUpRound r : p.rounds) {
                fus.add(new FollowUp(r.question, r.expectedArea.getText(), r.answerArea.getText()));
            }
            sb.append(renderQuestionBlock(p.number,
                    p.questionArea.getText(), p.answerArea.getText(), fus));
        }
        try {
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to write session TXT", e);
        }
    }

    private void closeSessionTxt() {
        if (sessionTxtPath == null) return;
        persistSessionTxt();   // final flush of the latest panel contents
        sessionTxtPath = null;
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

    /** Loads the app stylesheet onto a scene; no-op if the resource is missing. */
    private void applyStylesheet(Scene scene) {
        var url = getClass().getResource(STYLESHEET);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        } else {
            log.debug("Stylesheet resource not found: {}", STYLESHEET);
        }
    }

    /** Builds a FontIcon graphic from an Ikonli literal (e.g. "mdi2p-play"). */
    private static FontIcon icon(String literal) {
        FontIcon fi = new FontIcon(literal);
        fi.setIconSize(16);
        return fi;
    }

    /** Creates a Button with text, an icon graphic, and the given style classes. */
    @SuppressWarnings("unused")
    private static Button styledButton(String text, String iconLiteral, String... styleClasses) {
        Button btn = new Button(text);
        btn.setGraphic(icon(iconLiteral));
        btn.setGraphicTextGap(6);
        btn.getStyleClass().addAll(styleClasses);
        return btn;
    }

    private static ComboBox<AudioDeviceInfo> buildDeviceCombo(List<AudioDeviceInfo> devices) {
        ComboBox<AudioDeviceInfo> combo = new ComboBox<>();
        combo.getItems().addAll(devices);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setStyle(FORM_FONT_STYLE);
        HBox.setHgrow(combo, Priority.ALWAYS);
        if (!devices.isEmpty()) combo.getSelectionModel().selectFirst();
        return combo;
    }

    // Builds a Label for the top form rows at the enlarged form font size.
    private static Label formLabel(String text) {
        Label l = new Label(text);
        l.setStyle(FORM_FONT_STYLE);
        l.getStyleClass().add("form-label");
        return l;
    }

    private static void autoSelectByKeyword(ComboBox<AudioDeviceInfo> combo,
                                            List<AudioDeviceInfo> devices, String keyword) {
        String kw = keyword.toLowerCase();
        devices.stream()
                .filter(d -> d.name().toLowerCase().contains(kw))
                .findFirst()
                .ifPresent(d -> combo.getSelectionModel().select(d));
    }

    private void setSessionControlsEnabled(boolean enabled) {
        groqKeyField.setDisable(!enabled);
        companyField.setDisable(!enabled);
        candidateField.setDisable(!enabled);
        jobField.setDisable(!enabled);
        candidateCombo.setDisable(!enabled);
        sessionBtn.setDisable(!enabled);
        // sexCombo and scaleCombo stay enabled during a session so they can be set after Start.
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
        var cssUrl = getClass().getResource(STYLESHEET);
        if (cssUrl != null) {
            alert.getDialogPane().getStylesheets().add(cssUrl.toExternalForm());
            alert.getDialogPane().getStyleClass().add("app-dialog");
        }
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        var cssUrl = getClass().getResource(STYLESHEET);
        if (cssUrl != null) {
            alert.getDialogPane().getStylesheets().add(cssUrl.toExternalForm());
            alert.getDialogPane().getStyleClass().add("app-dialog");
        }
        alert.showAndWait();
    }

    // Wraps a region (e.g. the 3-column row) with a drag handle below it so the
    // user can resize its height. The region's children stretch to fill that height.
    private static Node wrapResizableRow(Region content, double startPrefHeight) {
        content.setPrefHeight(startPrefHeight);
        content.setMinHeight(80);

        Region handle = new Region();
        handle.setPrefHeight(8);
        handle.setMaxWidth(Double.MAX_VALUE);
        handle.setCursor(Cursor.S_RESIZE);
        handle.getStyleClass().add("resize-handle");

        double[] startScreenY = {0};
        double[] startHeight  = {0};

        handle.setOnMousePressed(e -> {
            startScreenY[0] = e.getScreenY();
            startHeight[0]  = content.getHeight() > 0 ? content.getHeight() : content.getPrefHeight();
            e.consume();
        });
        handle.setOnMouseDragged(e -> {
            double delta = e.getScreenY() - startScreenY[0];
            content.setPrefHeight(Math.max(80, startHeight[0] + delta));
            e.consume();
        });

        return new VBox(0, content, handle);
    }

    // Translates Groq HTTP error codes into actionable Portuguese messages.
    private static String translateLlmError(String msg) {
        if (msg == null) return "Erro desconhecido";
        if (msg.contains("HTTP_401")) return "API key inválida ou expirada. Verifique a chave do provedor selecionado.";
        if (msg.contains("HTTP_403")) return "Acesso negado (403). Verifique as permissões da API key.";
        if (msg.contains("HTTP_429")) return "Limite de requisições atingido. Troque de provedor ou aguarde alguns segundos.";
        if (msg.contains("HTTP_5"))   return "Erro interno do servidor do modelo. Tente novamente em instantes.";
        return msg;
    }

    // ── Star rating (parsed from the AI's trailing "RATING: n/max" line) ──────

    private static final Pattern RATING_PATTERN =
            Pattern.compile("(?im)^\\s*RATING:\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$");

    /** Returns {score, max}; score is -1 when no RATING line is present. */
    private static int[] parseRating(String text, int fallbackMax) {
        Matcher m = RATING_PATTERN.matcher(text);
        if (m.find()) return new int[]{ Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        return new int[]{ -1, fallbackMax };
    }

    private static String stripRatingLine(String text) {
        return text.replaceAll("(?im)^\\s*RATING:\\s*\\d+\\s*/\\s*\\d+\\s*$", "").trim();
    }

    // Leading [\s>#*_-]* tolerates markdown the model may wrap the header in
    // (e.g. GPT-OSS emits "**FOLLOW-UP QUESTIONS:**"); the trailing \b.*$ makes the
    // colon optional and swallows any trailing "**". Without this the whole follow-up
    // block leaks into the analysis body and no radios appear.
    private static final Pattern FOLLOWUP_HEADER_PATTERN =
            Pattern.compile("(?im)^[\\s>#*_-]*FOLLOW-?\\s?UP\\s+QUESTIONS\\b.*$");

    // package-private for FollowUpParsingTest
    static List<String> parseFollowUps(String text) {
        Matcher m = FOLLOWUP_HEADER_PATTERN.matcher(text);
        if (!m.find()) return List.of();
        String after = text.substring(m.end());
        List<String> result = new ArrayList<>();
        for (String line : after.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.toLowerCase().startsWith("rating:")) break;
            String stripped = trimmed
                    .replaceFirst("^\\s*(?:[-*•]\\s+|\\d+[.)]\\s+)", "")  // bullet / number prefix
                    .replaceAll("^\\*+|\\*+$", "")                          // surrounding **markdown**
                    .trim();
            if (!stripped.isEmpty()) result.add(stripped);
        }
        return result;
    }

    private static String stripFollowUpSection(String text) {
        Matcher m = FOLLOWUP_HEADER_PATTERN.matcher(text);
        if (m.find()) return text.substring(0, m.start());
        return text;
    }

    private static String renderStars(int score, int max) {
        if (score < 0 || max <= 0) return "";
        int s = Math.max(0, Math.min(score, max));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= max; i++) sb.append(i <= s ? '★' : '☆');
        return sb.append("  ").append(s).append('/').append(max)
                 .append(" — ").append(levelLabel(s, max)).toString();
    }

    // Maps a score onto the 5 named bands, proportionally for any scale (5 or 10).
    private static String levelLabel(int score, int max) {
        int level = Math.max(1, Math.min(5, (int) Math.ceil(score * 5.0 / max)));
        return switch (level) {
            case 1 -> "Unsatisfactory";
            case 2 -> "Needs Improvement";
            case 3 -> "Satisfactory";
            case 4 -> "Very Good";
            default -> "Excellent";
        };
    }

    private void onWindowClose() { if (sessionRunning) stopSession(); }

    @Override
    public void stop() { if (sessionRunning) stopSession(); }

    // ══════════════════════════════════════════════════════════════════════
    // QuestionPanel — one per interview question
    // ══════════════════════════════════════════════════════════════════════

    private class QuestionPanel {

        final int number;
        final AtomicBoolean active = new AtomicBoolean(true);

        // UI (FX thread only)
        TitledPane titledPane;
        private TextArea questionArea;
        private TextArea answerArea;
        private TextArea expectedArea;
        private TextArea analysisArea;
        private Label    partialLabel;
        private Label    ratingLabel;
        private Button   analyzeBtn;
        private Button   stopBtn;
        private Button   continueBtn;

        // Per-column section stacks: the initial area, then a divided section per
        // confirmed follow-up round (addRound appends to all three).
        private VBox questionStack;
        private VBox expectedStack;
        private VBox answerStack;

        // Follow-up round state (FX thread only)
        private final List<FollowUpRound> rounds = new ArrayList<>();
        private TextArea currentSink;       // live transcription target; updated on confirm
        private VBox followUpSelectBox;     // radios + OK (hidden until analysis produces options)
        private ToggleGroup followUpGroup;
        private final List<RadioButton> followUpRadios = new ArrayList<>();  // exactly 3
        private Button followUpOkBtn;

        QuestionPanel(int number) {
            this.number = number;
            buildUI();
        }

        void setInitialAnswer(String text) {
            answerArea.setText(text);
        }

        void setInitialQuestion(String text) {
            questionArea.setText(text);
        }

        // ── UI construction ──────────────────────────────────────────────

        private void buildUI() {
            // ── Three side-by-side columns, each a vertical section stack ─────
            answerArea   = pasteArea("A resposta do candidato aparece aqui…");
            expectedArea = pasteArea("Cole aqui a resposta esperada / gabarito…");
            questionArea = pasteArea("Cole aqui a pergunta…");
            answerArea.setPrefRowCount(8);
            expectedArea.setPrefRowCount(8);
            questionArea.setPrefRowCount(8);

            // Each column starts with the initial area; confirming a follow-up appends
            // a divider + labelled section to all three (see addRound). The whole panel
            // scrolls in the outer questions scroll pane, so the stacks grow freely.
            questionStack = new VBox(6, questionArea);
            expectedStack = new VBox(6, expectedArea);
            answerStack   = new VBox(6, answerArea);

            // SplitPane gives draggable dividers so the user can resize column widths.
            SplitPane columns = new SplitPane(
                    column("QUESTION",         questionStack),
                    column("EXPECTED ANSWER",  expectedStack),
                    column("CANDIDATE ANSWER", answerStack));
            columns.setDividerPositions(0.34, 0.67);

            partialLabel = new Label();
            partialLabel.getStyleClass().add("partial-label");
            partialLabel.setWrapText(true);
            partialLabel.setMaxWidth(Double.MAX_VALUE);
            partialLabel.setManaged(false);   // takes no space until there's live text
            partialLabel.setVisible(false);
            // Scale the live-preview text with the A−/A+ font control, like the areas.
            partialLabel.styleProperty().bind(Bindings.createStringBinding(
                    () -> "-fx-font-size: " + fontSize.get() + "px;", fontSize));

            // ── Buttons (with the AI star rating on the left) ──────────────────
            ratingLabel = new Label();
            ratingLabel.getStyleClass().add("rating-label");

            Region buttonsSpacer = new Region();
            HBox.setHgrow(buttonsSpacer, Priority.ALWAYS);

            Button copyBtn = new Button("Copy Analysis");
            copyBtn.setGraphic(icon("mdi2c-content-copy"));
            copyBtn.setGraphicTextGap(6);
            copyBtn.getStyleClass().add("btn-secondary");
            copyBtn.setOnAction(e -> onCopyAnalysis(copyBtn));

            analyzeBtn = new Button("Analisar");
            analyzeBtn.setGraphic(icon("mdi2r-robot"));
            analyzeBtn.setGraphicTextGap(6);
            analyzeBtn.getStyleClass().add("btn-primary");
            analyzeBtn.setOnAction(e -> analyze());

            continueBtn = new Button("Continue");
            continueBtn.setGraphic(icon("mdi2p-play"));
            continueBtn.setGraphicTextGap(6);
            continueBtn.getStyleClass().add("btn-secondary");
            continueBtn.setDisable(true);
            continueBtn.setOnAction(e -> resumeQuestion());

            stopBtn = new Button("Stop");
            stopBtn.setGraphic(icon("mdi2s-stop"));
            stopBtn.setGraphicTextGap(6);
            stopBtn.getStyleClass().add("btn-danger");
            stopBtn.setOnAction(e -> stopQuestion(this));

            HBox buttons = new HBox(8,
                    ratingLabel, buttonsSpacer, copyBtn, analyzeBtn, continueBtn, stopBtn);
            buttons.setAlignment(Pos.CENTER_LEFT);
            buttons.setPadding(new Insets(4, 0, 0, 0));

            // ── AI analysis output ───────────────────────────────────────────
            Label analysisLabel = sectionLabel("ANÁLISE DA IA");
            analysisArea = transcriptArea(6, "A análise da IA aparecerá aqui…");

            // ── Follow-up selection box (3 radios + OK; hidden until analysis) ──
            followUpGroup = new ToggleGroup();
            Label followUpSelectLabel = sectionLabel("ESCOLHA UM FOLLOW-UP");
            followUpSelectLabel.setGraphic(icon("mdi2c-comment-question-outline"));
            followUpSelectLabel.setGraphicTextGap(6);

            RadioButton r1 = new RadioButton();
            RadioButton r2 = new RadioButton();
            RadioButton r3 = new RadioButton();
            r1.setToggleGroup(followUpGroup);
            r2.setToggleGroup(followUpGroup);
            r3.setToggleGroup(followUpGroup);
            r1.setManaged(false); r1.setVisible(false);
            r2.setManaged(false); r2.setVisible(false);
            r3.setManaged(false); r3.setVisible(false);
            followUpRadios.add(r1);
            followUpRadios.add(r2);
            followUpRadios.add(r3);
            r1.styleProperty().bind(Bindings.createStringBinding(
                    () -> "-fx-font-size: " + fontSize.get() + "px;", fontSize));
            r2.styleProperty().bind(Bindings.createStringBinding(
                    () -> "-fx-font-size: " + fontSize.get() + "px;", fontSize));
            r3.styleProperty().bind(Bindings.createStringBinding(
                    () -> "-fx-font-size: " + fontSize.get() + "px;", fontSize));
            r1.setWrapText(true);
            r2.setWrapText(true);
            r3.setWrapText(true);

            followUpOkBtn = new Button("OK");
            followUpOkBtn.getStyleClass().add("btn-primary");
            followUpOkBtn.setDisable(true);
            followUpOkBtn.setOnAction(e -> confirmFollowUp());

            followUpGroup.selectedToggleProperty().addListener((o, was, now) ->
                    followUpOkBtn.setDisable(now == null));

            // Radios live in a scroll pane so the follow-up area can be dragged smaller
            // (via the resize handle) without ever clipping long, wrapped questions.
            VBox followUpRadiosBox = new VBox(6, r1, r2, r3, followUpOkBtn);
            ScrollPane followUpScroll = new ScrollPane(followUpRadiosBox);
            followUpScroll.setFitToWidth(true);
            followUpScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            followUpScroll.getStyleClass().add("flat-scroll");
            followUpSelectBox = new VBox(6, followUpSelectLabel,
                    wrapResizableRow(followUpScroll, 130));
            followUpSelectBox.setVisible(false);
            followUpSelectBox.setManaged(false);

            // Sink starts at the initial answer area
            currentSink = answerArea;

            // ── Content layout (Order invariant) ─────────────────────────────
            // 1. columns (grow with follow-up sections)  2. live preview  3. buttons/rating
            // 4. analysis (resizable)  5. follow-up radios (resizable)
            VBox content = new VBox(6,
                    columns,
                    partialLabel,
                    buttons,
                    new Separator(),
                    analysisLabel, wrapResizableRow(analysisArea, 160),
                    followUpSelectBox);
            content.setPadding(new Insets(10));

            titledPane = new TitledPane("Question " + number, content);
            FontIcon activeIcon = icon("mdi2r-record-circle");
            activeIcon.setIconColor(Color.web("#DC2626"));
            titledPane.setGraphic(activeIcon);
            titledPane.setExpanded(true);
            titledPane.setAnimated(true);
        }

        // Builds one SplitPane column: a section label above a vertical stack of
        // sections (initial area + one per follow-up). A small min width lets dividers
        // be dragged narrow without letting a column collapse to nothing.
        private VBox column(String labelText, VBox stack) {
            VBox col = new VBox(4, sectionLabel(labelText), stack);
            col.setMinWidth(60);
            return col;
        }

        // A compact section area for a follow-up row (read-only question view, or an
        // editable expected/answer area). Font follows the global A−/A+ control.
        private TextArea followUpArea(boolean editable, String prompt) {
            TextArea ta = editable ? pasteArea(prompt) : transcriptArea(3, prompt);
            ta.setPrefRowCount(3);
            ta.setEditable(editable);
            return ta;
        }

        // An editable, wrapping text area whose font follows the global A−/A+ control.
        private TextArea pasteArea(String prompt) {
            TextArea ta = new TextArea();
            ta.setEditable(true);
            ta.setWrapText(true);
            ta.styleProperty().bind(Bindings.createStringBinding(
                    () -> "-fx-font-size: " + fontSize.get() + "px;",
                    fontSize));
            ta.setPromptText(prompt);
            return ta;
        }

        private Label sectionLabel(String text) {
            Label l = new Label(text);
            l.getStyleClass().add("section-label");
            return l;
        }

        private TextArea transcriptArea(int rows, String prompt) {
            TextArea ta = new TextArea();
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setPrefRowCount(rows);
            ta.styleProperty().bind(Bindings.createStringBinding(
                    () -> "-fx-font-size: " + fontSize.get() + "px;",
                    fontSize));
            ta.setPromptText(prompt);
            return ta;
        }

        // Sets the live-preview text and collapses the label when it's empty, so an
        // empty preview chip never shows.
        private void setPartialText(String text) {
            partialLabel.setText(text == null ? "" : text);
            boolean show = text != null && !text.isBlank();
            partialLabel.setManaged(show);
            partialLabel.setVisible(show);
        }

        // ── Transcript events (arrive on STT thread → dispatched to FX thread) ─

        void onCandidateEvent(TranscriptEvent event) {
            Platform.runLater(() -> {
                if (event.isFinal()) {
                    // Always write to the current sink (initial area or newest round area)
                    TextArea sink = currentSink;
                    String existing  = sink.getText();
                    String separator = (existing.isEmpty() || existing.endsWith(" ")) ? "" : " ";
                    String updated   = existing + separator + event.text() + " ";
                    boolean userEditing = sink.isFocused();
                    int anchor = sink.getAnchor();
                    int caret  = sink.getCaretPosition();
                    double scrollTop = sink.getScrollTop();
                    sink.setText(updated);
                    if (userEditing) {
                        // Appended text only extends the tail, so the user's
                        // caret/selection indices are still valid — restore them.
                        sink.selectRange(anchor, caret);
                        sink.setScrollTop(scrollTop);
                    } else {
                        sink.positionCaret(updated.length());
                        sink.setScrollTop(Double.MAX_VALUE);
                    }
                    setPartialText("");
                    persistSessionTxt();
                } else {
                    setPartialText(event.text());
                }
            });
        }

        // ── Follow-up round management ───────────────────────────────────────

        /**
         * Shows up to 3 radio buttons populated with the model-generated follow-up options.
         * If {@code qs} is null or empty, hides the entire selection box.
         */
        private void showFollowUpOptions(List<String> qs) {
            if (qs == null || qs.isEmpty()) {
                followUpSelectBox.setVisible(false);
                followUpSelectBox.setManaged(false);
                for (RadioButton r : followUpRadios) {
                    r.setText("");
                    r.setManaged(false);
                    r.setVisible(false);
                }
                followUpGroup.selectToggle(null);
                followUpOkBtn.setDisable(true);
                return;
            }
            List<String> clamped = qs.size() > 3 ? qs.subList(0, 3) : qs;
            for (int i = 0; i < followUpRadios.size(); i++) {
                RadioButton r = followUpRadios.get(i);
                if (i < clamped.size()) {
                    r.setText(clamped.get(i));
                    r.setManaged(true);
                    r.setVisible(true);
                } else {
                    r.setText("");
                    r.setManaged(false);
                    r.setVisible(false);
                }
            }
            followUpGroup.selectToggle(null);
            followUpOkBtn.setDisable(true);
            followUpSelectBox.setVisible(true);
            followUpSelectBox.setManaged(true);
        }

        /** Handles OK: fixes the chosen follow-up, creates a round, generates its guide. */
        private void confirmFollowUp() {
            RadioButton sel = (RadioButton) followUpGroup.getSelectedToggle();
            if (sel == null) return;
            String fq = sel.getText();
            FollowUpRound r = addRound(fq, null, null, true);  // creates round, switches sink
            showFollowUpOptions(List.of()); // consume this set; hides box until next analysis
            persistSessionTxt();            // save the new (empty-answer) round immediately
            generateExpectedFor(r, fq);     // fill the EXPECTED guide (async, only for the chosen one)
        }

        /**
         * Appends a follow-up round across the three columns — a read-only question view
         * (QUESTION), an editable AI-guide area (EXPECTED ANSWER) and the candidate's
         * follow-up answer area (CANDIDATE ANSWER) — each preceded by a divider line.
         * Makes the new answer area the live transcription sink.
         *
         * @param question     the chosen follow-up question (fixed)
         * @param expectedText initial expected-guide text (null → empty; filled later live)
         * @param answerText   initial answer text (null → empty)
         * @param editable     true when created live; false when loaded from a saved file
         */
        private FollowUpRound addRound(String question, String expectedText,
                                       String answerText, boolean editable) {
            // Previous follow-up round answer becomes read-only context;
            // the initial answerArea is intentionally left editable (assumption 3).
            if (!rounds.isEmpty()) {
                rounds.get(rounds.size() - 1).answerArea.setEditable(false);
            }
            int n = rounds.size() + 1;

            TextArea qView = followUpArea(false, "");
            qView.setText(question);
            questionStack.getChildren().addAll(
                    new Separator(), sectionLabel("FOLLOW-UP " + n), qView);

            TextArea eArea = followUpArea(true, "Gabarito do follow-up (gerado pela IA)…");
            if (expectedText != null) eArea.setText(expectedText);
            expectedStack.getChildren().addAll(
                    new Separator(), sectionLabel("FOLLOW-UP " + n + " — GABARITO"), eArea);

            TextArea aArea = followUpArea(true, "Resposta do candidato ao follow-up…");
            aArea.setEditable(editable);
            if (answerText != null) aArea.setText(answerText);
            answerStack.getChildren().addAll(
                    new Separator(), sectionLabel("FOLLOW-UP " + n), aArea);

            FollowUpRound r = new FollowUpRound(question, qView, eArea, aArea);
            rounds.add(r);
            currentSink = aArea;   // Sink invariant: newest round is the live target
            return r;
        }

        /**
         * Generates the one-paragraph "reference points" guide for the chosen follow-up
         * and drops it into its EXPECTED area. Runs on a virtual thread; called only on
         * confirm, so the two unchosen follow-ups never cost a request. Uses the LLM
         * provider selected in the "Análise" dropdown.
         */
        private void generateExpectedFor(FollowUpRound r, String followUpQuestion) {
            LlmProvider provider = llmProviderCombo.getValue();
            String key = llmKeyFor(provider);
            if (key.isEmpty()) {
                r.expectedArea.setPromptText("Informe a chave de " + provider + " para gerar o gabarito.");
                return;
            }
            String jobDesc = jobDescArea.getText().trim();
            String iq = questionArea.getText().trim();
            String ia = answerArea.getText().trim();
            r.expectedArea.setPromptText("Gerando gabarito…");
            Thread.ofVirtual().name("followup-expected-q" + number).start(() -> {
                try {
                    String expected = GroqClient.generateFollowUpExpected(
                            provider, key, jobDesc, iq, ia, followUpQuestion);
                    Platform.runLater(() -> {
                        // Don't clobber anything the interviewer typed while it generated.
                        if (r.expectedArea.getText().isBlank() && expected != null) {
                            r.expectedArea.setText(expected.trim());
                        }
                        r.expectedArea.setPromptText("Gabarito do follow-up (editável)…");
                        persistSessionTxt();
                    });
                } catch (Exception ex) {
                    log.error("Follow-up expected generation failed (q{})", number, ex);
                    Platform.runLater(() -> r.expectedArea.setPromptText(
                            "Falha ao gerar gabarito: " + translateLlmError(ex.getMessage())));
                }
            });
        }

        /**
         * Adds a read-only round when reopening a saved session that contained follow-ups.
         * The panel will be {@code markStopped()} after all rounds load, so no live
         * transcription flows in.
         */
        void addLoadedRound(String question, String expected, String answer) {
            addRound(question, expected, answer, false);
        }

        // ── Stop / resume ────────────────────────────────────────────────

        void markStopped() {
            if (!active.compareAndSet(true, false)) return;
            Platform.runLater(() -> {
                stopBtn.setDisable(true);
                continueBtn.setDisable(!sessionRunning);
                setPartialText("");
                titledPane.setExpanded(false);
                updateTitle();
                log.info("Question {} stopped", number);
            });
        }

        void resumeQuestion() {
            if (!sessionRunning) return;
            QuestionPanel current = activeQuestion;
            if (current != null && current != this) current.markStopped();

            active.set(true);
            activeQuestion = this;

            Platform.runLater(() -> {
                stopBtn.setDisable(false);
                continueBtn.setDisable(true);
                titledPane.setExpanded(true);
                updateTitle();
                log.info("Question {} resumed", number);
            });
        }

        /** Called when the session starts or stops to keep Continue button state consistent. */
        void setContinueEnabled(boolean enabled) {
            Platform.runLater(() -> {
                if (!active.get()) continueBtn.setDisable(!enabled);
            });
        }

        // ── Helpers ─────────────────────────────────────────────────────

        private void updateTitle() {
            String q = questionArea.getText().trim();
            String preview = q.isEmpty() ? "" :
                    ": \"" + (q.length() > 55 ? q.substring(0, 55) + "…" : q) + "\"";
            titledPane.setText("Question " + number + preview);
            FontIcon statusIcon;
            if (active.get()) {
                statusIcon = icon("mdi2r-record-circle");
                statusIcon.setIconColor(Color.web("#DC2626"));
            } else {
                statusIcon = icon("mdi2c-check-circle");
                statusIcon.setIconColor(Color.web("#2563EB"));
            }
            titledPane.setGraphic(statusIcon);
        }

        /**
         * Sends the full exchange (initial Q&amp;A + all confirmed follow-up rounds) to Groq
         * for holistic evaluation, then shows the prose, star rating, and 3 new radio options.
         */
        private void analyze() {
            LlmProvider provider = llmProviderCombo.getValue();
            String key       = llmKeyFor(provider);
            String question  = questionArea.getText().trim();
            String expected  = expectedArea.getText().trim();
            String candidate = answerArea.getText().trim();
            String name      = candidateField.getText().trim();
            String sex       = sexCombo.getValue();   // "Male" / "Female" / null
            int    scale     = "10".equals(scaleCombo.getValue()) ? 10 : 5;
            String jobDesc   = jobDescArea.getText().trim();

            if (key.isEmpty())       { showAlert("Chave de API ausente", "Informe a chave de " + provider + " no painel \"Chaves de API\" no topo."); return; }
            if (question.isEmpty())  { showAlert("Pergunta vazia",  "Preencha ou cole a pergunta na coluna QUESTION."); return; }
            if (candidate.isEmpty()) { showAlert("Sem resposta",    "A coluna CANDIDATE ANSWER está vazia."); return; }
            // EXPECTED ANSWER is optional. Follow-up answer emptiness is NOT guarded —
            // re-analysis is allowed even if the newest round's answer is still empty.

            // Snapshot rounds on the FX thread before spawning the virtual thread
            List<GroqClient.FollowUpTurn> turns = new ArrayList<>();
            for (FollowUpRound r : rounds)
                turns.add(new GroqClient.FollowUpTurn(r.question, r.answerArea.getText().trim()));

            analyzeBtn.setDisable(true);
            analyzeBtn.setText("Analisando…");
            analysisArea.setText("");
            ratingLabel.setText("");
            showFollowUpOptions(List.of());   // clear any unconsumed previous set

            Thread.ofVirtual().name("answer-evaluate-q" + number).start(() -> {
                try {
                    String result = GroqClient.evaluateExchange(
                            provider, key, question, expected, candidate, turns, name, sex, scale, jobDesc);
                    int[] rating           = parseRating(result, scale);
                    List<String> followUps = parseFollowUps(result);
                    String body            = stripRatingLine(stripFollowUpSection(result));
                    Platform.runLater(() -> {
                        analysisArea.setText(body);
                        ratingLabel.setText(renderStars(rating[0], rating[1]));
                        showFollowUpOptions(followUps);
                        resetAnalyzeBtn();
                    });
                } catch (Exception ex) {
                    log.error("Per-question evaluation failed (q{})", number, ex);
                    String detail = translateLlmError(ex.getMessage());
                    Platform.runLater(() -> {
                        analysisArea.setText("Erro: " + detail);
                        showFollowUpOptions(List.of());
                        resetAnalyzeBtn();
                    });
                }
            });
        }

        private void resetAnalyzeBtn() {
            analyzeBtn.setDisable(false);
            analyzeBtn.setText("Analisar");
            analyzeBtn.setGraphic(icon("mdi2r-robot"));
        }

        private void onCopyAnalysis(Button btn) {
            String analysis = analysisArea.getText().trim();
            if (analysis.isEmpty()) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(analysis);
            Clipboard.getSystemClipboard().setContent(cc);
            btn.setText("✓  Copied!");
            PauseTransition p = new PauseTransition(Duration.seconds(1.5));
            p.setOnFinished(e -> {
                btn.setText("Copy Analysis");
                btn.setGraphic(icon("mdi2c-content-copy"));
            });
            p.play();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
