# Upstream algorithm source

The FusionCareer algorithm core will be selectively adapted from:

- Repository: `https://github.com/chenxin1209/FusionCareer-Algorithm`
- Commit: `4dc20862f79888e7e84351217767fa161a53ae83`
- License: MIT

Planned imports:

- Job structuring prompt, enum mapping, normalization, and deduplication.
- Resume PDF/image extraction, OCR, prompt, and normalization.

Excluded:

- Standalone FastAPI application and empty router.
- CLI, CSV/XLSX export, generated data, local config, and duplicate HTTP/LLM clients.

The imported code must use this repository's existing `LLMClient`, `BackendClient`,
Settings, Skill/Workflow registry, Scheduler, and runtime paths.
