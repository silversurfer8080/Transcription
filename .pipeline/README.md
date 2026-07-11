# `.pipeline/` — Delivery pipeline artifacts

Shared scratch folder for the `/ship` pipeline. Each stage communicates with the
next by writing a Markdown file here — the handoff is on disk, not in memory,
because each subagent starts cold without the others' context.

All files below are **generated** and git-ignored (see `.gitignore`). Only this
`README.md` is tracked, so the folder survives a fresh clone.

## Artifacts

| File          | Produced by      | Purpose                                  |
|---------------|------------------|------------------------------------------|
| `request.md`  | user (`/ship`)   | Original feature request — pipeline input |
| `specs.md`    | `planner-agent`  | Detailed implementation specification     |
| `changes.md`  | `coder-agent`    | Summary of the code changes made          |
| `tests.md`    | `tester-agent`   | Tests added/updated and their results     |
| `review.md`   | `reviewer-agent` | Final verdict and safe-to-merge statement |
| `blocked.md`  | any stage        | Written if the pipeline must stop early   |

## Flow

```
request.md ─▶ [planner] ─▶ specs.md
                              ▼
                          [coder] ─▶ changes.md   (│ blocked.md → stop)
                              ▼
                          [tester] ─▶ tests.md
                              ▼
                          [reviewer] ─▶ review.md
```

The pipeline runs stages sequentially and does **not** start a stage until the
previous stage's required artifact exists. If `blocked.md` appears, the run
stops immediately. Nothing is committed, merged, or pushed — `review.md` only
states whether the change is *safe* to merge.
