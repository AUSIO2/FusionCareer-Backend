"""Private subprocess entry point; executes unmodified upstream functions."""

from __future__ import annotations

import base64
import contextlib
import csv
import fcntl
import importlib.util
import json
import os
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

VENDOR = Path(__file__).resolve().parents[1] / "vendor" / "fusioncareer_algorithm"
sys.path.insert(0, str(VENDOR))
sys.path.insert(1, str(Path(__file__).resolve().parents[2]))


def local_path(root: Path, value: str) -> Path:
    path = (root / value).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Algorithm file path must stay inside its workspace")
    return path


def configure(root: Path, supplied: dict, options: dict) -> dict:
    from job_structuring import paths

    # Rebase upstream defaults instead of modifying the vendored source tree.
    paths.PROJECT_ROOT = str(root)
    paths.DATA_DIR = str(root / "data")
    paths.OUTPUT_DIR = str(root / "data/output")
    paths.LOGS_DIR = str(root / "logs")
    paths.DEFAULT_ARTICLES_DIR = str(root / "data/articles")
    paths.LEGACY_ARTICLES_DIR = str(root / "公众号文章")
    config = dict(supplied)
    allowed = {
        "llm_temperature", "llm_max_tokens", "admin_llm_max_tokens",
        "admin_llm_timeout_seconds", "llm_timeout_seconds", "llm_send_disable_thinking",
        "upload_batch_size", "upload_fetch_existing", "upload_fetch_page_size",
        "upload_timeout_seconds", "bootstrap_article_limit", "daily_mirror_to_dated_folder",
        "daily_folder_suffix", "min_file_size_kb", "delete_small_files",
        "llm_model", "llm_fast_model", "llm_extra_body",
    }
    if options.keys() - allowed:
        raise ValueError("Unsupported algorithm config option")
    config.update(options)
    paths.configure(config)
    paths.ensure_project_dirs()
    return config


def require_text(request: dict, field: str = "text") -> str:
    text = request.get(field)
    if not isinstance(text, str) or not text.strip():
        raise ValueError(f"{field} must be non-empty text")
    return text


def read_positions() -> list[dict]:
    from job_structuring import paths
    from job_structuring.engine import format_position_row

    path = Path(paths.csv_path())
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return [format_position_row(row) for row in csv.DictReader(stream)]


def jobs(operation: str, request: dict, root: Path, config: dict) -> dict:
    from job_structuring import engine, paths
    from job_structuring import admin_parse
    from job_structuring.export import export_positions_json
    from job_structuring.normalize import clean_company_name, sanitize_position_name

    call_llm = engine._llm_chat
    parse_items = engine._extract_positions_list
    dedup_key = engine._position_dedup_key
    append_row = engine._append_csv_row

    def normalized_key(source_url="", company="", position="", source_md=""):
        return dedup_key(source_url, clean_company_name(company), sanitize_position_name(position), source_md)

    def checked_append(row, path):
        if not append_row(row, path):
            raise RuntimeError("Upstream CSV write failed")
        return True

    # Upstream checks uncleaned names but stores cleaned names. Normalize both
    # sides of its existing dedup key so repeated runs do not insert duplicates.
    engine._position_dedup_key = normalized_key
    engine._append_csv_row = checked_append

    def strict_llm(*args, **kwargs):
        result = call_llm(*args, **kwargs)
        if result is None:
            latest = root / "logs/llm_io/latest.json"
            output = json.loads(latest.read_text()).get("output", "") if latest.exists() else ""
            if "402" in output or "insufficient balance" in output.lower():
                raise RuntimeError("LLM insufficient balance (402)")
            raise RuntimeError("Upstream job LLM request failed; see workspace LLM log")
        return result

    def strict_items(raw):
        result = parse_items(raw)
        if result is None:
            raise ValueError("Upstream job model returned invalid JSON")
        return result

    engine._llm_chat = admin_parse._llm_chat = strict_llm
    engine._extract_positions_list = admin_parse._extract_positions_list = strict_items

    if operation == "job_structure":
        operation = "job_admin" if request.get("sourceType", "PLATFORM") == "PLATFORM" else "job_markdown"
    if operation == "job_admin":
        result = admin_parse.parse_job_text(require_text(request), config)
        if result["meta"].get("error"):
            raise RuntimeError(f"Upstream job parsing failed: {result['meta']['error']}")
        return result
    if operation == "job_markdown":
        if "text" in request:
            path = local_path(root, "data/articles/input.md")
            text = require_text(request)
            source_url = request.get("sourceUrl") or ""
            if source_url and "**Link:**" not in text:
                text += f"\n\n**Link:** {source_url}\n"
            path.write_text(text, encoding="utf-8")
        else:
            path = local_path(root, request["file"])
        if not path.is_file():
            raise FileNotFoundError("Markdown file does not exist")
        engine.process_new_markdown(str(path), config)
    elif operation == "job_batch":
        # Workflow callers may supply documents directly or populate the workspace.
        for index, text in enumerate(request.get("documents", [])):
            local_path(root, f"data/articles/{index}.md").write_text(text, encoding="utf-8")
        directory = local_path(root, request.get("directory", "data/articles"))
        if not directory.is_dir():
            raise FileNotFoundError("Article directory does not exist")
        engine.run_batch_dir(str(directory), config)
    elif operation == "job_deduplicate":
        before, after = engine.deduplicate_csv_file(paths.csv_path())
        return {"before": before, "after": after, "csv": paths.csv_path()}
    elif operation == "job_export_json":
        return {"file": export_positions_json(), "positions": read_positions()}
    elif operation == "job_export_xlsx":
        return {"file": engine.export_positions_xlsx()}
    else:
        raise ValueError("Unsupported job operation")
    if not Path(paths.csv_path()).exists():
        with open(paths.csv_path(), "w", encoding="utf-8-sig", newline="") as stream:
            csv.DictWriter(stream, fieldnames=engine.POSITION_FIELDNAMES).writeheader()
    return {"positions": read_positions(), "csv": paths.csv_path()}


def resume_file(request: dict, root: Path, temp: Path) -> Path:
    suffix = Path(request.get("filename", "resume.pdf")).suffix.lower()
    if "file" in request:
        path = local_path(root, request["file"])
    else:
        if "file_base64" in request:
            encoded = request["file_base64"]
            if encoded.startswith("data:"):
                header, encoded = encoded.split(",", 1)
                suffix = {"application/pdf": ".pdf", "image/png": ".png", "image/jpeg": ".jpg",
                          "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx"}.get(
                              header[5:].split(";")[0], suffix)
            if len(encoded) > 28 * 1024 * 1024:
                raise ValueError("Resume exceeds 20 MB")
            data = base64.b64decode(encoded, validate=True)
        elif "file_url" in request:
            import requests
            from urllib.parse import urlparse

            url = request["file_url"]
            if urlparse(url).scheme not in {"http", "https"}:
                raise ValueError("Resume URL must use HTTP or HTTPS")
            suffix = Path(urlparse(url).path).suffix.lower() or suffix
            with requests.get(url, stream=True, timeout=30) as response:
                response.raise_for_status()
                data = bytearray()
                for chunk in response.iter_content(65536):
                    data.extend(chunk)
                    if len(data) > 20 * 1024 * 1024:
                        raise ValueError("Resume exceeds 20 MB")
        else:
            raise ValueError("Resume needs raw_text, file, file_base64 or file_url")
        if len(data) > 20 * 1024 * 1024:
            raise ValueError("Resume exceeds 20 MB")
        path = temp / ("resume" + suffix)
        path.write_bytes(data)
    if path.suffix.lower() not in {".pdf", ".docx", ".png", ".jpg", ".jpeg"}:
        raise ValueError("Unsupported resume format")
    if path.stat().st_size > 20 * 1024 * 1024:
        raise ValueError("Resume exceeds 20 MB")
    return path


def resumes(operation: str, request: dict, root: Path, config: dict) -> dict:
    from resume_parser.parser import ResumeParser
    from resume_parser.llm.deepseek_client import DeepSeekClient

    parser = ResumeParser(api_key=config["llm_api_key"])
    parser._llm = DeepSeekClient(config["llm_api_key"], config["llm_model"], config["llm_base_url"])
    if operation == "resume_batch":
        files = [local_path(root, name) for name in request["files"]]
        for path in files:
            resume_file({"file": str(path)}, root, root)
        output = local_path(root, request.get("output", "data/output/resumes.csv"))
        output.parent.mkdir(parents=True, exist_ok=True)
        parser.parse_batch_to_csv([str(path) for path in files], str(output))
        return {"file": str(output), "count": len(files), "metrics": parser.last_metrics}
    if "raw_text" in request:
        result = parser.parse_text(require_text(request, "raw_text"))
    else:
        with TemporaryDirectory(prefix="resume-", dir=root) as directory:
            result = parser.parse(str(resume_file(request, root, Path(directory))))
    return {"record": result, "metrics": parser.last_metrics}


def load_wechat(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, VENDOR / "wechat_crawler" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def wechat(operation: str, request: dict, root: Path, config: dict) -> dict:
    if operation == "wechat_sync_session":
        module = load_wechat("upstream_session", "sync_wechat_session.py")
        module.CONFIG_FILE = root / "wechat-session.json"
        if not module.run_cdp(require_text(request, "cdp_url")):
            raise RuntimeError("WeChat session synchronization failed")
        os.chmod(module.CONFIG_FILE, 0o600)
        return {"synced": True}
    session = root / "wechat-session.json"
    if session.exists():
        config.update(json.loads(session.read_text()))
    if not config.get("token") or not config.get("cookie"):
        raise ValueError("WeChat credentials are not configured")
    module = load_wechat("upstream_crawler", "wechat_crawler.py.py")
    module.CONFIG_FILE = str(root / "wechat-config.json")
    load_json = module.load_json
    module.load_json = lambda path: config if path == module.CONFIG_FILE else load_json(path)
    failures = []
    http_get = module.http_get

    def checked_get(url, **kwargs):
        try:
            response = http_get(url, **kwargs)
            response.raise_for_status()
            if "/cgi-bin/" in url:
                body = response.json()
                if body.get("base_resp", {}).get("ret", 0) != 0 or "publish_page" not in body:
                    raise RuntimeError("WeChat API rejected the session or response")
            return response
        except Exception:
            failures.append(True)
            raise

    get_articles = module.get_articles
    save_article = module.save_url_to_md

    def checked_articles(*args, **kwargs):
        result = get_articles(*args, **kwargs)
        if failures:
            raise RuntimeError("WeChat article listing failed")
        return result

    def checked_save(*args, **kwargs):
        result = save_article(*args, **kwargs)
        if result == "error" or failures:
            raise RuntimeError("WeChat article download failed")
        return result

    module.http_get = checked_get
    module.get_articles = checked_articles
    module.save_url_to_md = checked_save
    accounts = request.get("accounts")
    if not accounts:
        raise ValueError("accounts must contain fakeid/name entries")
    fakeids = [str(account["fakeid"]) for account in accounts]
    names = {index: account.get("name", fakeids[index]) for index, account in enumerate(accounts)}
    module.HISTORY_FILE = str(root / "history.json")
    module.OUTPUT_FILE = str(root / "wx_poc.txt")
    articles = str(root / "data/articles")
    args = (fakeids, config["token"], config["cookie"])
    if operation == "wechat_bootstrap":
        module.mode_bootstrap(*args, names, int(config.get("bootstrap_article_limit", 10)), articles)
        return {"accounts": len(accounts), "directory": articles}
    if operation == "wechat_archive":
        module.mode_archive(*args, names, articles)
        return {"accounts": len(accounts), "directory": articles}
    daily = str(local_path(root, module.get_daily_increment_dir(config, articles)))
    if config.get("daily_mirror_to_dated_folder", True):
        Path(daily).mkdir(parents=True, exist_ok=True)
    else:
        daily = None
    stats = module.mode_update(*args, module.load_json(module.HISTORY_FILE), names, articles, daily_mirror_dir=daily)
    time = module.beijing_now().strftime("%Y-%m-%d %H:%M:%S")
    report = {"time": time, "total_new": sum(stats.values()), "by_account": stats}
    with (root / "daily_report.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=False) + "\n")
    if daily:
        module.write_daily_summary_md(str(Path(daily) / "新增统计.md"), stats, time, Path(daily).name, articles)
    return {**report, "directory": articles, "daily_directory": daily}


def upload(request: dict, root: Path, config: dict) -> dict:
    from job_structuring import upload_backend
    from app.algorithms.job_structuring.normalize import normalizeJob
    import requests

    # Preserve upstream upload dedup/state/batching while using the deployed Java auth contract.
    session = requests.Session()
    session.headers["X-Internal-Token"] = config["internal_service_token"]
    upload_backend.requests = session
    def offline(row):
        payload, warnings = normalizeJob(row, row.get("sourceUrl") or "", row.get("sourceType") or "CRAWL")
        return None if warnings else payload

    upload_backend.row_to_job_post_payload = offline
    try:
        result = upload_backend.upload_structured_jobs(
            config,
            json_path=str(local_path(root, request["json"])) if request.get("json") else None,
            csv_path=str(local_path(root, request["csv"])) if request.get("csv") else None,
            dry_run=request.get("dry_run", True), fetch_existing=request.get("fetch_existing"),
        )
        if result["failed_batches"]:
            raise RuntimeError("Upstream upload reported failed batches")
        return result
    finally:
        session.close()


def execute(payload: dict) -> dict:
    root = Path(payload["root"]).resolve()
    request = payload["request"]
    config = configure(root, payload["config"], request.get("config", {}))
    operation = payload["operation"]
    if operation == "job_upload":
        return upload(request, root, config)
    if operation.startswith("job_"):
        return jobs(operation, request, root, config)
    if operation.startswith("resume_"):
        return resumes(operation, request, root, config)
    if operation.startswith("wechat_"):
        files = [root / "history.json", root / "wx_poc.txt"]
        previous = {path: path.read_bytes() if path.exists() else None for path in files}
        try:
            return wechat(operation, request, root, config)
        except BaseException:
            # Upstream writes article logs before downloads. Roll back cursor/log state
            # on failure so the next scheduled run can retry the same articles.
            for path, content in previous.items():
                if content is None:
                    path.unlink(missing_ok=True)
                else:
                    path.write_bytes(content)
            raise
    raise ValueError("Unknown algorithm operation")


def main():
    os.umask(0o077)
    payload = json.load(sys.stdin)
    root = Path(payload["root"])
    # ponytail: serialize writers within one workspace; distinct workspaces run in parallel.
    with (root / ".lock").open("a") as lock, contextlib.redirect_stdout(sys.stderr):
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            result = {"result": execute(payload)}
        except Exception as error:
            message = str(error)
            for secret in payload["config"].values():
                if isinstance(secret, str) and secret:
                    message = message.replace(secret, "[redacted]")
            result = {"error": {"type": type(error).__name__, "message": message[:500]}}
    json.dump(result, sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    main()
