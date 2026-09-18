# FusionCareer-Algorithm integration

- Repository: https://github.com/chenxin1209/FusionCareer-Algorithm
- Pinned commit: `72ff6b2b44520f78090958f0c3a7e74959189646` (2026-09-15)
- License: MIT, preserved in the vendor directory and THIRD_PARTY_LICENSES.
- Source: `app/vendor/fusioncareer_algorithm/`.
- `PROVENANCE.json` records SHA-256 hashes of every upstream tracked file.
  These files are copied without modifications; adaptations belong outside them.

All algorithm capabilities are exposed through the existing workflow engine.
`upstream_algorithm` executes the original Python functions in an isolated worker;
the Agent remains the only HTTP server. Standalone HTTP/CLI functions become
Agent workflows; watch/cron become Scheduler jobs.

See `docs/ALGORITHM_WORKFLOWS.md` in the Backend repository for the capability map,
request examples, deployment requirements and adapter differences.

The old June implementation remains for explicit Python LLMClient callers and
regression coverage. Java endpoints and the running Agent's default official/WeChat
structuring use the pinned upstream workflows.
