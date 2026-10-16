"""Source statistics from the deployed CrawlStore; count each article URL once."""

from __future__ import annotations

import json
import sqlite3
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


def source_stats(source: Path, output: Path, workspace: Path, jobs: list[dict]) -> dict:
    from pipeline.report_source_stats import is_journalism_related, scan_archive_tree, write_csv

    db = source / "crawl.db"
    if not db.exists():
        db = source / "wechat.db"
    records = []
    if db.exists():
        with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
            conn.row_factory = sqlite3.Row
            records = [
                dict(r)
                for r in conn.execute(
                    "SELECT a.*, coalesce(s.name, a.fakeid) AS source_name FROM articles a "
                    "LEFT JOIN accounts s ON s.fakeid=a.fakeid"
                )
            ]
    counts = defaultdict(lambda: {"file_count": 0, "job_count": 0, "journalism_job_count": 0})
    sources = {}
    for article in records:
        date = str(article.get("published_at") or article.get("created_at") or "unknown")
        if date.isdigit():
            date = datetime.fromtimestamp(int(date), ZoneInfo("Asia/Shanghai")).strftime("%Y-%m-%d")
        else:
            date = date[:10]
        article_path = Path(article.get("markdown_path") or "")
        kind = "official" if "官网文章" in article_path.parts else "wechat"
        name = article_path.parent.name if kind == "official" else article["source_name"]
        key = (date, kind, name)
        counts[key]["file_count"] += 1
        sources[article["url"]] = key
    if not db.exists():
        # Archive trees are authoritative; dated mirrors and daily reports are not extra articles.
        for folder, kind in (("公众号文章", "wechat"), ("官网文章", "official")):
            for row in scan_archive_tree(str(source / folder), kind):
                counts[(row.get("date") or "unknown", kind, row["source_name"])]["file_count"] += 1
    seen = set()
    for job in jobs:
        identity = job.get("id") or (job.get("sourceUrl"), job.get("companyName"), job.get("positionName"))
        if identity in seen:
            continue
        seen.add(identity)
        key = sources.get(job.get("sourceUrl"), (str(job.get("createdAt") or "unknown")[:10], "other", "未关联来源"))
        counts[key]["job_count"] += 1
        counts[key]["journalism_job_count"] += int(
            is_journalism_related({**job, "description": job.get("jobDesc", "")})
        )
    rows = [{"date": d, "source_type": t, "source_name": n, **values} for (d, t, n), values in sorted(counts.items())]
    totals = defaultdict(lambda: {"file_count": 0, "job_count": 0, "journalism_job_count": 0})
    for row in rows:
        for field in ("file_count", "job_count", "journalism_job_count"):
            totals[(row["source_type"], row["source_name"])][field] += row[field]
    summary_rows = [
        {
            "source_type": t,
            "source_name": n,
            **v,
            "journalism_ratio": round(v["journalism_job_count"] / v["job_count"], 4) if v["job_count"] else 0,
        }
        for (t, n), v in sorted(totals.items())
    ]
    output.mkdir(parents=True, exist_ok=True)
    columns = ["source_type", "source_name", "file_count", "job_count", "journalism_job_count"]
    write_csv(str(output / "daily_source_stats.csv"), rows, ["date", *columns])
    write_csv(str(output / "source_totals.csv"), summary_rows, [*columns, "journalism_ratio"])
    summary = {
        "sources": len(totals),
        "jobs": len(seen),
        "articles": sum(r["file_count"] for r in rows),
        "basis": "unique CrawlStore URLs + Java jobs" if db.exists() else "archive trees + Java jobs",
    }
    summary.update(
        {
            "jobs_loaded": len(seen),
            "daily_rows": len(rows),
            "generated_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
            "note": "新闻相关岗位为关键词启发式，需人工抽检",
        }
    )
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False), encoding="utf-8")
    return {"summary": summary, "files": [str(p.relative_to(workspace)) for p in output.glob("*.csv")]}
