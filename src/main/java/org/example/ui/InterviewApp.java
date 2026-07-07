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
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.Main;
import org.example.audio.AudioCapture;
import org.example.audio.AudioDeviceInfo;
import org.example.audio.AudioDevices;
import org.example.llm.GroqClient;
import org.example.stt.GroqWhisperProvider;
import org.example.stt.SpeechToTextProvider;
import org.example.stt.TranscriptEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private PasswordField groqKeyField;   // Groq key — STT (Whisper) + per-question AI evaluation
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
        // ── Row 1: Groq key + power button ──────────────────────────────────
        groqKeyField = new PasswordField();
        groqKeyField.setPromptText("Groq API key  (gsk_...)");
        groqKeyField.setStyle(FORM_FONT_STYLE);
        groqKeyField.getStyleClass().add("field");
        HBox.setHgrow(groqKeyField, Priority.ALWAYS);
        String groqEnv = System.getenv("GROQ_API_KEY");
        if (groqEnv != null && !groqEnv.isBlank()) groqKeyField.setText(groqEnv);

        Button powerBtn = new Button("");
        powerBtn.setGraphic(icon("mdi2p-power"));
        powerBtn.getStyleClass().addAll("btn-icon", "btn-danger");
        powerBtn.setTooltip(new Tooltip("Fechar aplicação"));
        powerBtn.setOnAction(e -> { if (sessionRunning) stopSession(); Platform.exit(); });

        HBox row1 = new HBox(8,
                formLabel("Groq:"), groqKeyField,
                powerBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── Row 2: name + job + company + sex + stars ────────────────────────
        // Compact fixed widths so Sex and Stars fit comfortably on the same row.
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

        VBox controls = new VBox(8, row1, row2, row3, row4);
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

    // ── Session lifecycle ──────────────────────────────────────────────────

    private void onSessionToggle() {
        if (sessionRunning) stopSession(); else startSession();
    }

    private void startSession() {
        String groqKey = groqKeyField.getText().trim();
        if (groqKey.isEmpty()) {
            showAlert("Chave ausente", "Informe a Groq API key — ela é usada para a transcrição.");
            return;
        }

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

        Thread.ofVirtual().name("start-session").start(() -> {
            try {
                // Only the candidate channel is captured and transcribed.
                // Batch Whisper on Groq's free tier: buffers ~5 s windows and POSTs
                // them. A failed POST (bad key, rate limit) is surfaced on the
                // status dot via the error listener.
                GroqWhisperProvider cProv = new GroqWhisperProvider(groqKey, "candidate");
                cProv.setErrorListener(msg -> Platform.runLater(() -> {
                    statusDot.setFill(Color.ORANGE);
                    log.warn("Groq STT: {}", msg);
                }));

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

        // Create one read-only panel per section
        for (LoadedQuestion lq : sections) {
            questionCounter++;
            QuestionPanel panel = new QuestionPanel(questionCounter);
            panel.setInitialQuestion(lq.question());
            panel.setInitialAnswer(lq.answer());
            panel.markStopped();
            questionPanels.add(panel);
            questionsBox.getChildren().add(panel.titledPane);
        }

        saveSessionBtn.setDisable(false);
        log.info("Loaded {} question(s) from {}", sections.size(), file.getName());
        Platform.runLater(() -> questionsScroll.setVvalue(0.0));
    }

    /** A question section parsed back from a saved session TXT. */
    private record LoadedQuestion(String question, String answer) {}

    private static List<LoadedQuestion> parseSessionFile(Path path) throws IOException {
        List<LoadedQuestion> sections = new ArrayList<>();
        List<String> currentLines = null;

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.matches("--- Pergunta \\d+ ---")) {
                if (currentLines != null) sections.add(splitQuestionAnswer(currentLines));
                currentLines = new ArrayList<>();
            } else if (currentLines != null) {
                currentLines.add(line);   // keep blanks: they separate question from answer
            }
        }
        if (currentLines != null) sections.add(splitQuestionAnswer(currentLines));
        return sections;
    }

    // New format: <question lines> <blank> <answer lines>. Old format (no blank
    // line) is treated as answer-only so legacy files still load correctly.
    private static LoadedQuestion splitQuestionAnswer(List<String> lines) {
        int firstBlank = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) { firstBlank = i; break; }
        }
        if (firstBlank < 0) {
            return new LoadedQuestion("", joinNonBlank(lines));
        }
        return new LoadedQuestion(
                joinNonBlank(lines.subList(0, firstBlank)),
                joinNonBlank(lines.subList(firstBlank + 1, lines.size())));
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

    // ── Transcript routing (WebSocket threads → QuestionPanel) ────────────

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

    // Rewrites the whole session TXT from the current panels, one block per question:
    //   --- Pergunta N ---
    //   <question column>
    //
    //   <candidate answer>
    private void persistSessionTxt() {
        Path path = sessionTxtPath;
        if (path == null) return;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (QuestionPanel p : questionPanels) {
            if (!first) sb.append(System.lineSeparator());
            first = false;
            sb.append("--- Pergunta ").append(p.number).append(" ---").append(System.lineSeparator());
            sb.append(p.questionArea.getText().trim()).append(System.lineSeparator());
            sb.append(System.lineSeparator());
            sb.append(p.answerArea.getText().trim()).append(System.lineSeparator());
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
    private static String translateGroqError(String msg) {
        if (msg == null) return "Erro desconhecido";
        if (msg.contains("HTTP_401")) return "API key Groq inválida ou expirada. Verifique a chave e tente novamente.";
        if (msg.contains("HTTP_403")) return "Acesso negado (403). Verifique as permissões da API key.";
        if (msg.contains("HTTP_429")) return "Limite de requisições atingido. Aguarde alguns segundos e tente novamente.";
        if (msg.contains("HTTP_5"))   return "Erro interno do servidor Groq. Tente novamente em instantes.";
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

    private static final Pattern FOLLOWUP_HEADER_PATTERN =
            Pattern.compile("(?im)^\\s*FOLLOW-?\\s?UP\\s+QUESTIONS\\s*:.*$");

    private static List<String> parseFollowUps(String text) {
        Matcher m = FOLLOWUP_HEADER_PATTERN.matcher(text);
        if (!m.find()) return List.of();
        String after = text.substring(m.end());
        List<String> result = new ArrayList<>();
        for (String line : after.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.toLowerCase().startsWith("rating:")) break;
            String stripped = trimmed.replaceFirst("^\\s*(?:[-*•]\\s+|\\d+[.)]\\s+)", "").trim();
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
        private VBox     followUpBox;
        private TextArea followUpArea;
        private Label    partialLabel;
        private Label    ratingLabel;
        private Button   analyzeBtn;
        private Button   stopBtn;
        private Button   continueBtn;

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
            // ── Three side-by-side columns ───────────────────────────────────
            answerArea   = pasteArea("A resposta do candidato aparece aqui…");
            expectedArea = pasteArea("Cole aqui a resposta esperada / gabarito…");
            questionArea = pasteArea("Cole aqui a pergunta…");

            // SplitPane gives draggable dividers so the user can resize column widths.
            SplitPane columns = new SplitPane(
                    column("QUESTION",         questionArea),
                    column("EXPECTED ANSWER",  expectedArea),
                    column("CANDIDATE ANSWER", answerArea));
            columns.setDividerPositions(0.34, 0.67);

            partialLabel = new Label();
            partialLabel.getStyleClass().add("partial-label");
            partialLabel.setWrapText(true);
            partialLabel.setMaxWidth(Double.MAX_VALUE);

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

            // ── Follow-up questions section (hidden until populated) ─────────
            Label followUpLabel = sectionLabel("PERGUNTAS DE FOLLOW-UP");
            followUpLabel.setGraphic(icon("mdi2c-comment-question-outline"));
            followUpLabel.setGraphicTextGap(6);
            followUpArea = transcriptArea(3, "");
            followUpBox = new VBox(6, followUpLabel, followUpArea);
            followUpBox.setVisible(false);
            followUpBox.setManaged(false);

            VBox content = new VBox(6,
                    wrapResizableRow(columns, 150),
                    partialLabel,
                    buttons,
                    new Separator(),
                    followUpBox,
                    analysisLabel, analysisArea);
            content.setPadding(new Insets(10));

            titledPane = new TitledPane("Question " + number, content);
            FontIcon activeIcon = icon("mdi2r-record-circle");
            activeIcon.setIconColor(Color.web("#DC2626"));
            titledPane.setGraphic(activeIcon);
            titledPane.setExpanded(true);
            titledPane.setAnimated(true);
        }

        // Builds one SplitPane column: a section label above a text area that
        // stretches to fill the pane. A small min width lets dividers be dragged
        // narrow without letting a column collapse to nothing.
        private VBox column(String labelText, TextArea ta) {
            VBox.setVgrow(ta, Priority.ALWAYS);
            ta.setMaxHeight(Double.MAX_VALUE);
            VBox col = new VBox(4, sectionLabel(labelText), ta);
            col.setMaxHeight(Double.MAX_VALUE);
            col.setMinWidth(60);
            return col;
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

        // ── Transcript events (arrive on WebSocket thread) ───────────────

        void onCandidateEvent(TranscriptEvent event) {
            Platform.runLater(() -> {
                if (event.isFinal()) {
                    // Read current textarea text (may include user edits) and append
                    String existing  = answerArea.getText();
                    String separator = (existing.isEmpty() || existing.endsWith(" ")) ? "" : " ";
                    String updated   = existing + separator + event.text() + " ";
                    boolean userEditing = answerArea.isFocused();
                    int anchor = answerArea.getAnchor();
                    int caret  = answerArea.getCaretPosition();
                    double scrollTop = answerArea.getScrollTop();
                    answerArea.setText(updated);
                    if (userEditing) {
                        // Appended text only extends the tail, so the user's
                        // caret/selection indices are still valid — restore them.
                        answerArea.selectRange(anchor, caret);
                        answerArea.setScrollTop(scrollTop);
                    } else {
                        answerArea.positionCaret(updated.length());
                        answerArea.setScrollTop(Double.MAX_VALUE);
                    }
                    partialLabel.setText("");
                    persistSessionTxt();
                } else {
                    partialLabel.setText(event.text());
                }
            });
        }

        // ── Stop / resume ────────────────────────────────────────────────

        void markStopped() {
            if (!active.compareAndSet(true, false)) return;
            Platform.runLater(() -> {
                stopBtn.setDisable(true);
                continueBtn.setDisable(!sessionRunning);
                partialLabel.setText("");
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

        // Sends question + expected answer + candidate transcription to Groq and
        // renders the structured evaluation into the analysis box below the columns.
        private void analyze() {
            String key       = groqKeyField.getText().trim();
            String question  = questionArea.getText().trim();
            String expected  = expectedArea.getText().trim();
            String candidate = answerArea.getText().trim();
            String name      = candidateField.getText().trim();
            String sex       = sexCombo.getValue();   // "Male" / "Female" / null
            int    scale     = "10".equals(scaleCombo.getValue()) ? 10 : 5;
            String jobDesc   = jobDescArea.getText().trim();

            if (key.isEmpty())       { showAlert("Groq API Key ausente", "Informe a Groq API key no campo \"Groq\" no topo."); return; }
            if (question.isEmpty())  { showAlert("Pergunta vazia",  "Preencha ou cole a pergunta na coluna QUESTION."); return; }
            if (candidate.isEmpty()) { showAlert("Sem resposta",    "A coluna CANDIDATE ANSWER está vazia."); return; }
            // EXPECTED ANSWER is optional — when empty, the AI judges using its own expertise.

            analyzeBtn.setDisable(true);
            analyzeBtn.setText("Analisando…");
            analysisArea.setText("");
            ratingLabel.setText("");
            showFollowUps(List.of());

            Thread.ofVirtual().name("answer-evaluate-q" + number).start(() -> {
                try {
                    String result     = GroqClient.evaluateAnswer(key, question, expected, candidate, name, sex, scale, jobDesc);
                    int[] rating      = parseRating(result, scale);
                    List<String> followUps = parseFollowUps(result);
                    String body       = stripRatingLine(stripFollowUpSection(result));
                    Platform.runLater(() -> {
                        analysisArea.setText(body);
                        ratingLabel.setText(renderStars(rating[0], rating[1]));
                        showFollowUps(followUps);
                        resetAnalyzeBtn();
                    });
                } catch (Exception ex) {
                    log.error("Per-question evaluation failed (q{})", number, ex);
                    String detail = translateGroqError(ex.getMessage());
                    Platform.runLater(() -> { analysisArea.setText("Erro: " + detail); showFollowUps(List.of()); resetAnalyzeBtn(); });
                }
            });
        }

        private void resetAnalyzeBtn() {
            analyzeBtn.setDisable(false);
            analyzeBtn.setText("Analisar");
            analyzeBtn.setGraphic(icon("mdi2r-robot"));
        }

        private void showFollowUps(List<String> qs) {
            if (qs == null || qs.isEmpty()) {
                followUpArea.clear();
                followUpBox.setVisible(false);
                followUpBox.setManaged(false);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < qs.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(i + 1).append(". ").append(qs.get(i));
            }
            followUpArea.setText(sb.toString());
            followUpBox.setVisible(true);
            followUpBox.setManaged(true);
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
