# Development Flow

This project uses a small branch-based flow with Gradle CI as the baseline quality gate.

## Branching

- Start each task from the current integration branch, usually `main`.
- Use descriptive task branches, for example `feature/KAN-1-configure-ci-baseline`.
- Keep each branch focused on one task or one coherent fix.
- Do not commit local artifacts, generated build output, API keys, logs, or recordings.

## Local Checks

Run the full build before opening a pull request:

```bash
./gradlew build
```

The build compiles the Java 21 codebase and runs the JUnit 5 test suite. Use narrower Gradle tasks while developing, but treat `build` as the required pre-PR check.

## Pull Requests

- Fill out the pull request template.
- Link the task or issue that explains the reason for the change.
- Include any manual validation notes when UI, audio capture, packaging, or provider integration behavior changes.
- Keep the PR description current if the implementation changes during review.

## Review And Merge

- CI must pass before merge.
- At least one reviewer should check behavior, test coverage, and risk areas.
- Use the Codex review checklist for AI-assisted review passes.
- Prefer small follow-up tasks over expanding a PR beyond its original scope.

## Release Readiness

Before release-oriented changes, confirm:

- `./gradlew build` passes on a clean checkout.
- Any packaging changes are tested on their target OS.
- Runtime configuration and required environment variables are documented.
- External provider changes avoid logging secrets or full sensitive responses.
