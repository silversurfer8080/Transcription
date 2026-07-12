# Codex Review Checklist

Use this checklist when asking Codex to review a pull request or branch. The goal is to find concrete risks, not to restate the diff.

## Correctness

- Does the implementation satisfy the task without adding unrelated behavior?
- Are edge cases handled explicitly, especially for audio format conversion, stream lifecycle, provider errors, and UI state?
- Are exceptions handled at the right boundary with useful, sanitized messages?
- Are resources closed deterministically, including audio lines, files, HTTP/WebSocket clients, and background threads?

## Tests

- Do tests cover the changed behavior and likely failure modes?
- Are tests deterministic and independent from local audio devices, network access, API keys, and wall-clock timing?
- If manual validation is required, is it documented in the PR?
- Does `./gradlew build` pass?

## Security And Privacy

- Are API keys and secrets kept out of source, logs, screenshots, and test fixtures?
- Are provider error responses sanitized before display or logging?
- Are recordings, transcripts, and local data paths excluded from commits unless intentionally added?

## Maintainability

- Does the change follow existing package boundaries and Gradle conventions?
- Is new abstraction justified by repeated behavior or clear ownership?
- Is user-facing documentation updated when workflow, setup, or runtime behavior changes?
- Are comments reserved for non-obvious decisions?

## CI/CD

- Does the workflow use Java 21 and the Gradle wrapper?
- Are CI jobs scoped to compile and test the project without requiring local hardware, credentials, or GUI access?
- Are workflow permissions limited to what the job needs?
- Are cache and concurrency settings safe for pull requests?
