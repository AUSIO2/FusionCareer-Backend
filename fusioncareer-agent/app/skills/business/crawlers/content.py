"""采集来源共用的时间、文件名和正文转换。"""

import re
from datetime import datetime
from zoneinfo import ZoneInfo

BEIJING_TZ = ZoneInfo("Asia/Shanghai")


def beijing_now() -> datetime:
    return datetime.now(BEIJING_TZ)


def clean_filename(title: str | None) -> str:
    return re.sub(r'[\\/*?:"<>|]', "", (title or "")).strip()


def html_to_markdown(html: str) -> str:
    html = re.sub(r"<style.*?>.*?</style>", "", html, flags=re.DOTALL)
    html = re.sub(r"<script.*?>.*?</script>", "", html, flags=re.DOTALL)

    def replace_img(match):
        src = match.group(1) or match.group(2)
        return f"\n![]({src})\n"

    html = re.sub(r'<img[^>]+data-src="([^"]+)"[^>]*>', replace_img, html)
    html = re.sub(r'<img[^>]+src="([^"]+)"[^>]*>', replace_img, html)

    def replace_pre_code(match):
        code_content = match.group(1)
        code_content = re.sub(r"<code[^>]*>(.*?)</code>", r"\1", code_content, flags=re.DOTALL)
        code_content = (
            code_content.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", '"')
            .replace("&nbsp;", " ")
        )
        return f"\n```\n{code_content}\n```\n"

    html = re.sub(r"<pre[^>]*>(.*?)</pre>", replace_pre_code, html, flags=re.DOTALL)
    html = re.sub(r"<code[^>]*>(.*?)</code>", r"`\1`", html, flags=re.DOTALL)
    for i in range(6, 0, -1):
        html = re.sub(f"<h{i}[^>]*>(.*?)</h{i}>", "#" * i + r" \1\n", html)
    html = re.sub(r"<p[^>]*>", "\n", html)
    html = re.sub(r"</p>", "\n", html)
    html = re.sub(r"<br\s*/?>", "\n", html)
    html = re.sub(r"<(b|strong)[^>]*>(.*?)</\1>", r"**\2**", html)
    html = re.sub(r"<li[^>]*>(.*?)</li>", r"- \1\n", html)
    html = re.sub(r"<[^>]+>", "", html)
    html = (
        html.replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", '"')
    )
    html = re.sub(r"\n{3,}", "\n\n", html)
    html = re.sub(r" +", " ", html)
    return html.strip()
