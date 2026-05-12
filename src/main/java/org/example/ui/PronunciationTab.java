package org.example.ui;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.llm.AnthropicClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pronunciation coaching tab.
 *
 * <p>The user selects one of the current session's recorded questions from the
 * dropdown — the transcription is pre-filled automatically. They can then
 * optionally narrow the analysis to a specific word or phrase, and request a
 * Claude-powered pronunciation critique tailored for Brazilian Portuguese
 * speakers of English.
 */
public class PronunciationTab {

    private static final Logger log = LoggerFactory.getLogger(PronunciationTab.class);

    private final ObservableList<QuestionRef> sessionQuestions;

    private PasswordField apiKeyField;
    private ComboBox<QuestionRef> questionCombo;
    private TextArea transcriptArea;
    private TextField focusWordField;
    private TextArea resultArea;
    private Button analyzeBtn;

    public PronunciationTab(ObservableList<QuestionRef> sessionQuestions) {
        this.sessionQuestions = sessionQuestions;
    }

    public Node buildContent() {
        // ── Row 1: API key ───────────────────────────────────────────────
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("Anthropic API key");
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) apiKeyField.setText(envKey);

        HBox row1 = new HBox(8, new Label("API Key:"), apiKeyField);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── Row 2: question dropdown ─────────────────────────────────────
        questionCombo = new ComboBox<>(sessionQuestions);
        questionCombo.setPromptText("Selecione uma questão da sessão…");
        questionCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(questionCombo, Priority.ALWAYS);
        questionCombo.setOnAction(e -> onQuestionSelected());

        HBox row2 = new HBox(8, new Label("Questão:"), questionCombo);
        row2.setAlignment(Pos.CENTER_LEFT);

        // ── Transcript ───────────────────────────────────────────────────
        Label transcriptLabel = sectionLabel("TRANSCRIÇÃO (editável)");
        transcriptArea = new TextArea();
        transcriptArea.setWrapText(true);
        transcriptArea.setPrefRowCount(5);
        transcriptArea.setStyle("-fx-font-size: 13px;");
        transcriptArea.setPromptText("Selecione uma questão acima, ou cole a transcrição aqui…");

        // ── Row 3: focus word ────────────────────────────────────────────
        focusWordField = new TextField();
        focusWordField.setPromptText("Palavra ou trecho específico (opcional — deixe vazio para analisar tudo)");
        HBox.setHgrow(focusWordField, Priority.ALWAYS);

        HBox row3 = new HBox(8, new Label("Foco:"), focusWordField);
        row3.setAlignment(Pos.CENTER_LEFT);

        // ── Analyze button ───────────────────────────────────────────────
        analyzeBtn = new Button("Analisar Pronúncia");
        analyzeBtn.setMaxWidth(Double.MAX_VALUE);
        analyzeBtn.setOnAction(e -> onAnalyze());

        // ── Result ───────────────────────────────────────────────────────
        Label resultLabel = sectionLabel("ANÁLISE");
        resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.setStyle("-fx-font-size: 13px;");
        resultArea.setPromptText("O resultado da análise aparecerá aqui…");
        VBox.setVgrow(resultArea, Priority.ALWAYS);

        VBox content = new VBox(10,
                row1,
                new Separator(),
                row2,
                transcriptLabel, transcriptArea,
                row3,
                analyzeBtn,
                new Separator(),
                resultLabel, resultArea);
        content.setPadding(new Insets(12));
        VBox.setVgrow(resultArea, Priority.ALWAYS);
        return content;
    }

    private void onQuestionSelected() {
        QuestionRef ref = questionCombo.getValue();
        if (ref == null) return;
        transcriptArea.setText(ref.getTranscript().get().trim());
    }

    private void onAnalyze() {
        String apiKey     = apiKeyField.getText().trim();
        String transcript = transcriptArea.getText().trim();
        String focusWord  = focusWordField.getText().trim();

        if (apiKey.isEmpty())     { showAlert("API Key ausente",  "Informe a Anthropic API key."); return; }
        if (transcript.isEmpty()) { showAlert("Sem transcrição",  "Selecione ou cole uma transcrição para analisar."); return; }

        analyzeBtn.setDisable(true);
        analyzeBtn.setText("Analisando…");
        resultArea.setText("");

        Thread.ofVirtual().name("pronunciation-analyze").start(() -> {
            try {
                String result = AnthropicClient.analyzePronunciation(apiKey, transcript, focusWord);
                Platform.runLater(() -> {
                    resultArea.setText(result);
                    resetBtn();
                });
            } catch (Exception ex) {
                log.error("Pronunciation analysis failed", ex);
                String detail = ex.getMessage() != null ? sanitizeError(ex.getMessage()) : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    resultArea.setText("Erro: " + detail);
                    resetBtn();
                });
            }
        });
    }

    private void resetBtn() {
        analyzeBtn.setDisable(false);
        analyzeBtn.setText("Analisar Pronúncia");
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #666;");
        return l;
    }

    private static String sanitizeError(String msg) {
        return msg.replaceAll("(?i)(token|key|auth(orization)?)[^\\s]*\\s*[=:]?\\s*\\S+", "[REDACTED]");
    }

    private static void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
