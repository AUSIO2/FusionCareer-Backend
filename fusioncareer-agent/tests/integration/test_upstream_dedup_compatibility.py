"""Verify the pinned upstream history format against the installed dependency."""

import subprocess
import sys
from pathlib import Path


def test_upstream_datasketch_history_round_trip(tmp_path):
    vendor = Path(__file__).parents[2] / "app/vendor/fusioncareer_algorithm"
    text = "招聘新闻编辑，负责采访、新闻写作与视频内容制作。" * 20
    canonical = tmp_path / "canonical.md"
    duplicate = tmp_path / "duplicate.md"
    canonical.write_text(text, encoding="utf-8")
    duplicate.write_text(text, encoding="utf-8")
    script = """
import sys
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from datasketch import MinHash
from job_structuring import paths
from job_structuring.article_dedup import (
    ArticleDoc, _load_history, _minhash, _save_history, ngrams, select_unique_markdowns,
)

root = Path(sys.argv[2])
paths.LOGS_DIR = str(root / "logs")
canonical = root / "canonical.md"
duplicate = root / "duplicate.md"
text = canonical.read_text(encoding="utf-8")
grams = ngrams(text, 3)
signature = _minhash(grams, 128)
assert isinstance(signature, MinHash)
config = {"article_dedup_index": str(root / "article_minhash.json")}
_save_history([ArticleDoc(str(canonical), "编辑招聘", text, grams, signature, len(text))], config)
history = _load_history(config, 128)
assert len(history) == 1
assert history[0][2] == str(canonical)
assert history[0][1].jaccard(signature) == 1.0
keep, dropped = select_unique_markdowns([str(duplicate)], config)
assert keep == []
assert len(dropped) == 1
assert dropped[0].kept_path == str(canonical)
assert dropped[0].jaccard == 1.0
"""
    result = subprocess.run(
        [sys.executable, "-c", script, str(vendor), str(tmp_path)],
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    assert result.returncode == 0, result.stderr
