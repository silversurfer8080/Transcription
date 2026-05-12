package org.example.ui;

import java.nio.file.Path;
import java.util.function.Supplier;

public record QuestionRef(int number, Path wavPath, Supplier<String> getTranscript) {

    @Override
    public String toString() {
        return "Q" + number;
    }
}
