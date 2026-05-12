package org.example.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.llm.AnthropicClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Candidate answer evaluation tab.
 *
 * <p>The interviewer pastes (1) the question they asked, (2) the expected
 * answer / key points, and (3) the candidate's transcribed answer — then
 * clicks Evaluate to get a structured Claude-powered assessment.
 */
public class EvaluationTab {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTab.class);

    private PasswordField apiKeyField;
    private TextArea questionArea;
    private TextArea expectedArea;
    private TextArea candidateArea;
    private TextArea resultArea;
    private Button evaluateBtn;

    public Node buildContent() {
        // ── Row 1: API key ───────────────────────────────────────────────
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("Anthropic API key");
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) apiKeyField.setText(envKey);

        HBox row1 = new HBox(8, new Label("API Key:"), apiKeyField);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── Input areas ──────────────────────────────────────────────────
        Label qLabel   = sectionLabel("PERGUNTA FEITA AO CANDIDATO");
        questionArea   = inputArea(2, "Cole aqui a pergunta que você fez ao candidato…");

        Label eLabel   = sectionLabel("RESPOSTA ESPERADA (gabarito / pontos-chave)");
        expectedArea   = inputArea(4, "Cole aqui os pontos-chave ou resposta modelo…");

        Label cLabel   = sectionLabel("TRANSCRIÇÃO DA RESPOSTA DO CANDIDATO");
        candidateArea  = inputArea(4,
                "Cole aqui a transcrição do candidato (use o botão \"📋 Copy Answer\" na aba Interview)…");

        // ── Evaluate button ──────────────────────────────────────────────
        evaluateBtn = new Button("Avaliar Resposta");
        evaluateBtn.setMaxWidth(Double.MAX_VALUE);
        evaluateBtn.setOnAction(e -> onEvaluate());

        // ── Result ───────────────────────────────────────────────────────
        Label resultLabel = sectionLabel("AVALIAÇÃO");
        resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.setStyle("-fx-font-size: 13px;");
        resultArea.setPromptText("A avaliação do candidato aparecerá aqui…");
        VBox.setVgrow(resultArea, Priority.ALWAYS);

        VBox content = new VBox(10,
                row1,
                new Separator(),
                qLabel,  questionArea,
                eLabel,  expectedArea,
                cLabel,  candidateArea,
                evaluateBtn,
                new Separator(),
                resultLabel, resultArea);
        content.setPadding(new Insets(12));
        VBox.setVgrow(resultArea, Priority.ALWAYS);
        return content;
    }

    private void onEvaluate() {
        String apiKey    = apiKeyField.getText().trim();
        String question  = questionArea.getText().trim();
        String expected  = expectedArea.getText().trim();
        String candidate = candidateArea.getText().trim();

        if (apiKey.isEmpty())    { showAlert("API Key ausente",  "Informe a Anthropic API key."); return; }
        if (question.isEmpty())  { showAlert("Pergunta vazia",   "Informe a pergunta feita ao candidato."); return; }
        if (expected.isEmpty())  { showAlert("Gabarito vazio",   "Informe a resposta esperada."); return; }
        if (candidate.isEmpty()) { showAlert("Sem transcrição",  "Cole a transcrição do candidato."); return; }

        evaluateBtn.setDisable(true);
        evaluateBtn.setText("Avaliando…");
        resultArea.setText("");

        Thread.ofVirtual().name("answer-evaluate").start(() -> {
            try {
                String result = AnthropicClient.evaluateAnswer(apiKey, question, expected, candidate);
                Platform.runLater(() -> {
                    resultArea.setText(result);
                    resetBtn();
                });
            } catch (Exception ex) {
                log.error("Answer evaluation failed", ex);
                String detail = ex.getMessage() != null ? sanitizeError(ex.getMessage()) : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    resultArea.setText("Erro: " + detail);
                    resetBtn();
                });
            }
        });
    }

    private void resetBtn() {
        evaluateBtn.setDisable(false);
        evaluateBtn.setText("Avaliar Resposta");
    }

    private static String sanitizeError(String msg) {
        return msg.replaceAll("(?i)(token|key|auth(orization)?)[^\\s]*\\s*[=:]?\\s*\\S+", "[REDACTED]");
    }

    private static TextArea inputArea(int rows, String prompt) {
        TextArea ta = new TextArea();
        ta.setWrapText(true);
        ta.setPrefRowCount(rows);
        ta.setStyle("-fx-font-size: 13px;");
        ta.setPromptText(prompt);
        return ta;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #666;");
        return l;
    }

    private static void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
