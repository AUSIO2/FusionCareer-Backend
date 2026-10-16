"""Deployment adapters for pinned upstream operations; loaded only inside a worker."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


def execute_enhanced(operation: str, request: dict, root: Path, config: dict, vendor: Path) -> dict:
    from app.algorithms.upstream_worker import local_path

    if operation == "job_prefilter":
        from job_structuring.prefilter import collect_terms, should_extract

        text = request.get("text", "")
        if request.get("file"):
            text = local_path(root, request["file"]).read_text(encoding="utf-8")
        if request.get("directory"):
            results = [
                {"file": str(p.relative_to(root)), **should_extract(p.read_text(encoding="utf-8"), config=config)}
                for p in local_path(root, request["directory"]).rglob("*.md")
                if p.resolve().is_relative_to(root)
            ]
            return {"results": results, "passed": sum(not r.get("skipped") for r in results)}
        result = should_extract(text, config=config)
        result["lexiconVersion"] = hashlib.sha256(
            json.dumps(
                {
                    "terms": collect_terms(config),
                    "minHits": config.get("prefilter_min_hits", 1),
                },
                ensure_ascii=False,
                sort_keys=True,
            ).encode()
        ).hexdigest()[:16]
        return result
    if operation == "job_source_stats":
        from app.algorithms.source_stats import source_stats

        source = Path(config.get("crawl_root") or root).resolve()
        if request.get("directory"):
            source = local_path(root, request["directory"])
        output = local_path(root, "data/output/source_stats")
        return source_stats(source, output, root, request.get("jobs", []))
    if operation.startswith("weread_"):
        from app.algorithms.weread_adapter import run_weread

        return run_weread(operation, request, root, config, vendor)

    from job_recommend import keywords
    from job_recommend.cities import UNLIMITED, parse_cities
    from job_recommend.filters import query_from_slots
    from job_structuring import engine

    # Both upstream modules resolve resources against a mutable PROJECT_ROOT.
    keywords._lexicon_path = lambda: str(vendor / "job_recommend/data/keyword_lexicon.json")
    if not keywords.load_keyword_lexicon().get("job_titles"):
        raise ValueError("Recommendation keyword lexicon is missing")
    # Interactive inputs must never enter upstream full I/O logs.
    engine.log_llm_io = lambda **kwargs: None
    engine.load_config = lambda: config
    slots = dict(request.get("slots") or {})
    if operation == "job_recommend_prepare":
        categories = list(dict.fromkeys(request.get("jobCategories") or []))
        cities = []
        for city in request.get("workCities") or []:
            if city.strip() in UNLIMITED:
                continue
            cities.extend(parse_cities(city) or [city.strip().removesuffix("市")])
        cities = list(dict.fromkeys(cities))
        terms = request.get("keywords") or []
        if request.get("text"):
            terms = [*terms, *keywords.extract_keywords(request["text"], use_llm=False)["all"]]
        terms = list(dict.fromkeys(terms))[:20]
        slots.update(
            {
                "workCities": cities,
                "keywordList": terms,
                "recruitType": request.get("recruitType"),
                "jobCategory": categories[0] if len(categories) == 1 else None,
            }
        )
        return {
            "slots": slots,
            "filters": {
                "jobCategories": categories,
                "workCities": cities,
                "keywords": terms,
                "recruitType": request.get("recruitType"),
            },
            "handoff": query_from_slots(slots).to_handoff(),
        }
    if operation == "job_recommend_turn":
        from job_recommend.dialogue import next_turn

        return next_turn(
            request.get("user_text", ""), slots, request.get("resume"), request.get("selections")
        ).to_dict()
    if operation != "job_recommend_rank":
        raise ValueError("Unsupported recommendation operation")

    # Validate the model order before the upstream function consumes it. Failure keeps rule order.
    llm = engine._llm_chat
    applied = False
    jobs = [dict(j) for j in request.get("jobs", [])]
    for job in jobs:
        job["id"] = str(job["id"])
        job["weeklyRecommend"] = job.get("recommended") is True
        if job.get("workCities"):
            job["workCity"] = "、".join(job["workCities"])

    def checked_chat(messages, cfg, **kwargs):
        nonlocal applied
        raw = llm(messages, cfg, **kwargs)
        try:
            payload = json.loads(raw or "{}")
            order = payload.get("order")
            preview = json.loads(messages[-1]["content"])["jobs"]
            ids = {str(j["id"]) for j in preview}
            if not isinstance(order, list) or not order:
                return None
            clean = []
            for item in order:
                if isinstance(item, (str, int)) and not isinstance(item, bool):
                    if str(item) in ids:
                        clean.append(str(item))
                    elif isinstance(item, int) and 0 <= item < len(preview):
                        clean.append(str(preview[item]["id"]))
            applied = bool(clean)
            if not applied:
                return None
            reasons = payload.get("reasons")
            clean_reasons = {}
            if isinstance(reasons, dict):
                reason_items = reasons.items()
            elif isinstance(reasons, list):
                reason_items = enumerate(reasons)
            else:
                reason_items = ()
            for key, value in reason_items:
                target = str(key)
                if target not in ids:
                    try:
                        index = int(key)
                    except (TypeError, ValueError):
                        continue
                    if index < 0 or index >= len(preview):
                        continue
                    target = str(preview[index]["id"])
                if isinstance(value, str) and value.strip():
                    clean_reasons[target] = " ".join(value.split())[:240]
            return json.dumps({"order": list(dict.fromkeys(clean)), "reasons": clean_reasons}, ensure_ascii=False)
        except (ValueError, TypeError, KeyError, AttributeError):
            return None

    from job_recommend import rank

    rank._llm_chat = checked_chat
    use_llm = bool(request.get("use_llm", True))
    result = rank.rank_jobs(
        jobs,
        slots,
        request.get("resume"),
        use_llm=use_llm,
        config=config,
        user_text=str(request.get("user_text") or "")[:1000],
    )
    result["method"] = "rules+llm" if applied else "rules"
    result["degraded"] = use_llm and bool(jobs) and not applied
    return result
