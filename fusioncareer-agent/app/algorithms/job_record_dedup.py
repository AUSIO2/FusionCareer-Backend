"""High-confidence job deduplication across reposted source articles.

Article similarity is deliberately not used here: university mirrors often wrap
the same employer campaign in very different page text.  A cross-source match
requires the same normalized position and company identity plus a stable
application URL, with explicit campaign-year and field-conflict guards.
"""

from __future__ import annotations

import html
import json
import re
import unicodedata
from calendar import monthrange
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, datetime
from functools import lru_cache
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, unquote, urlencode, urlsplit
from zoneinfo import ZoneInfo

_BRACKETED = re.compile(r"[（(]([^（）()]{1,160})[）)]")
_EMAIL = re.compile(
    r"(?<![A-Z0-9_.+-])([A-Z0-9._%+-]{1,64}@[A-Z0-9.-]{1,253}\.[A-Z]{2,63})"
    r"(?![A-Z0-9_.-])",
    re.IGNORECASE,
)
_URL = re.compile(r"https?://[^\s<>\[\]{}\"'，。；、）)]+", re.IGNORECASE)
_BARE_URL = re.compile(
    r"(?<![/@A-Z0-9_-])((?:[A-Z0-9-]+\.)+[A-Z]{2,63}(?:/[^\s<>\[\]{}\"'，。；、）)]*)?)",
    re.IGNORECASE,
)
_YEAR = re.compile(r"(?<!\d)(20\d{2})(?:\s*届)?(?!\d)")
_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)
_NON_MAJOR_TOKEN = re.compile(r"[^\w+#./]+", re.UNICODE)
_MULTI_SLASH = re.compile(r"/{2,}")
_TRACKING_QUERY_KEYS = {
    "from", "fromsource", "source", "spm", "trackid", "ref", "referrer", "locale", "lang", "language",
    "channel", "channelid", "channel_id",
    "utm_campaign", "utm_content", "utm_medium", "utm_source", "utm_term",
}
_TEXT_FIELDS = ("jobDesc", "reqSkills", "reqOther", "contactInfo")
_RECYCLED_VALUES = {3, "3", "RECYCLED"}
_EXPIRED_VALUES = {2, "2", "EXPIRED"}
_OTHER_RECRUIT_VALUES = {None, "", 6, "6", "OTHER"}
_RECRUIT_TYPE_NUMBERS = {
    1: "BIG_INTERNSHIP", 2: "SMALL_INTERNSHIP", 3: "DAILY_INTERNSHIP",
    4: "CAMPUS_RECRUITMENT", 5: "CAMPUS_SCREENING", 7: "BOTH_INTERNSHIP",
}
_WORK_MODE_NUMBERS = {1: "ONLINE", 2: "OFFLINE", 3: "HYBRID"}
_CITY_SEPARATOR = re.compile(r"[、,，;/；]+")
_COMPANY_ALIASES_PATH = Path(__file__).with_name("job_structuring") / "company_aliases.json"
_COMPANY_PART_HINT = re.compile(
    r"公司|集团|银行|大学|学院|研究院|研究所|中心|事务所|医院|委员会|政府|传媒|科技",
)
_DESCRIPTIVE_COMPANY_PARTS = {
    "上市公司", "国有企业", "国有公司", "国企", "央企", "民营企业", "外资企业", "合资企业",
    "招聘主体", "用人单位", "母公司", "子公司", "集团总部", "总部",
}
_APPLICATION_CUE = re.compile(r"网申|投递|申请|报名|招聘官网|职位链接|岗位链接|简历")
_CLAUSE_BOUNDARY = re.compile(r"[；;。\n]")
_ATS_HOST_SUFFIXES = (
    "zhiye.com", "mokahr.com", "jobs.feishu.cn", "51job.com", "liepin.com", "hotjob.cn",
)
_JOB_QUERY_KEYS = {
    "id", "jobid", "job_id", "positionid", "position_id", "requisitionid", "requisition_id",
    "recruitmentid", "recruitment_id", "zwdm", "jobcode", "job_code", "jobno", "job_no",
}
_JOB_PATH_HINTS = {"job", "jobs", "position", "positions", "requisition", "requisitions", "zpxx"}
_EXTERNAL_JOB_ID = re.compile(
    r"(?:职位|岗位|招聘)\s*(?:id|编号|编码|代码)|"
    r"(?:job|position|requisition)\s*(?:id|code|no\.?)",
    re.IGNORECASE,
)
_EXTERNAL_JOB_ID_VALUE = re.compile(
    r"^\s*(?:[:：#]|为|是|-)?\s*([A-Z0-9][A-Z0-9._/-]{1,63})",
    re.IGNORECASE,
)
_EXTERNAL_JOB_ID_STOPWORDS = {"none", "null", "unknown", "n/a", "na"}
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f-]{27,}$", re.IGNORECASE)
_STRONG_ADDRESS_CUE = re.compile(r"号|楼|座|栋|园区|大厦|校区|弄|巷|室")
_STREET_ADDRESS_CUE = re.compile(r"路|街|大道")
_NON_ADDRESS_STREET_WORD = re.compile(r"思路|线路|铁路|公路|道路")
_REGION_CUE = re.compile(r"北京|上海|天津|重庆|[\u4e00-\u9fff]{2,8}(?:省|市|区|县)")
_BUILDING_STAGE_CUE = re.compile(r"\d|[a-z]{1,8}\s*\d", re.IGNORECASE)
_UNKNOWN_CITY_TOKENS = {"不限", "不限地点", "地点不限", "待定", "暂无", "无"}
_NATIONAL_COVERAGE_CITY = re.compile(
    r"^全国(?:(?:\d+(?:多|余)?个?)|多个)?(?:主要)?(?:城市|地区)",
)
_NUMERIC_RANGE_SEPARATOR = re.compile(r"[‐‑‒–—―~～至]")
_PERSISTED_EVIDENCE = re.compile(
    r"^dedup-evidence:v1;keeper=(\d+);role=(keeper-before|incoming);sha256=[0-9a-f]{64}$",
)
_TRUSTED_MANUAL_MERGE_EVIDENCE = re.compile(
    r"^semantic-merge:[^;]+;keeper=(\d+)(?:;|$)",
)
_TRUSTED_R2_EVIDENCE = re.compile(
    r"^semantic-dedup:huice-production-dedup-v2-delta;keeper=(\d+)(?:;|$)",
)
_PERSISTED_SET_HARD_EVIDENCE = (
    "jobUrls", "externalJobIds", "years", "seasons", "cities", "majorKeys", "educationKeys",
    "eligibilityKeys",
)
_PERSISTED_SCALAR_HARD_EVIDENCE = (
    "departmentKey", "recruitFamily",
    "headcountKey", "headcountDisplayKey", "salaryMinKey", "salaryMaxKey",
    "salaryDisplayKey", "workModeKey", "workLocationKey",
    "workStartDate", "workEndDate", "workDaysPerWeekKey",
    "workDurationTypeKey", "workPeriodTypeKey", "workProvinceKey",
)


def normalizeText(readValue: Any) -> str:
    if readValue is None:
        return ""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", str(readValue)).strip()).casefold()


def normalizePosition(readValue: Any) -> str:
    # Symbols such as C++, C#, .NET and A/B carry role meaning and must survive.
    return re.sub(r"\s+", "", normalizeText(readValue))


def canonicalUrl(readValue: Any) -> str:
    """Return a scheme-insensitive URL identity with tracking parameters removed."""
    raw = html.unescape(str(readValue or "")).strip().rstrip(".,;:!?，。；：！？、）)]}")
    if not raw:
        return ""
    if "://" not in raw:
        raw = "https://" + raw
    try:
        parsed = urlsplit(raw)
        host = (parsed.hostname or "").casefold().rstrip(".")
        if not host:
            return ""
        host = host.removeprefix("www.")
        port = parsed.port
        if port and port not in {80, 443}:
            host = f"{host}:{port}"
        path = unicodedata.normalize(
            "NFKC", _MULTI_SLASH.sub("/", unquote(parsed.path or "/")),
        ).rstrip("/") or "/"
        query = [
            (unicodedata.normalize("NFKC", key.strip()), unicodedata.normalize("NFKC", value.strip()))
            for key, value in parse_qsl(parsed.query, keep_blank_values=True)
            if normalizeText(key) not in _TRACKING_QUERY_KEYS
            and not normalizeText(key).startswith("utm_")
        ]
        querySuffix = f"?{urlencode(sorted(query))}" if query else ""
        fragment = unicodedata.normalize("NFKC", unquote(parsed.fragment)).strip().replace(" ", "")
        if not fragment.strip("/"):
            fragment = ""
        # Keep SPA business identifiers such as #/jobs/123, but discard
        # presentational anchors such as #apply.
        keepFragment = fragment and ("/" in fragment or any(char.isdigit() for char in fragment))
        fragmentSuffix = f"#{fragment}" if keepFragment else ""
        return f"{host}{path}{querySuffix}{fragmentSuffix}"
    except (TypeError, ValueError):
        return ""


def _companyNameTokens(readName: Any) -> set[str]:
    raw = unicodedata.normalize("NFKC", str(readName or "")).strip()
    if not raw:
        return set()
    bracketed = [
        value for value in _BRACKETED.findall(raw)
        if _COMPANY_PART_HINT.search(value) and normalizeText(value) not in _DESCRIPTIVE_COMPANY_PARTS
    ]
    outside = _BRACKETED.sub(" ", raw)
    aliases: set[str] = set()
    for value in [outside, *bracketed]:
        token = _NON_WORD.sub("", normalizeText(value))
        if not token:
            continue
        aliases.add(token)
    return aliases


@lru_cache(maxsize=1)
def _companyAliasRegistry() -> dict[str, str]:
    try:
        payload = json.loads(_COMPANY_ALIASES_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return {}
    registry: dict[str, str] = {}
    for company in payload.get("companies", []):
        canonical = normalizeText(company.get("id"))
        if not canonical:
            continue
        for alias in company.get("aliases", []):
            for token in _companyNameTokens(alias):
                registry[token] = canonical
    return registry


def companyAliases(readName: Any) -> frozenset[str]:
    """Split brand/legal forms and attach versioned explicit company identities."""
    aliases = _companyNameTokens(readName)
    registry = _companyAliasRegistry()
    aliases.update(f"company:{registry[token]}" for token in list(aliases) if token in registry)
    return frozenset(aliases)


def _jobSpecificApplicationUrl(readUrl: str) -> bool:
    withoutFragment, _, fragment = readUrl.partition("#")
    withoutQuery, _, query = withoutFragment.partition("?")
    queryKeys = {key.casefold() for key, _value in parse_qsl(query, keep_blank_values=True)}
    if queryKeys & _JOB_QUERY_KEYS:
        return True
    path = "/" + withoutQuery.split("/", 1)[1] if "/" in withoutQuery else "/"
    parts = [part for part in path.casefold().split("/") if part]
    for index, part in enumerate(parts):
        if index and parts[index - 1] in _JOB_PATH_HINTS and (len(part) >= 6 or part.isdigit()):
            return True
        if _UUID.fullmatch(part):
            return True
    fragmentParts = [part for part in fragment.casefold().split("/") if part]
    return any(
        index and fragmentParts[index - 1] in _JOB_PATH_HINTS and (len(part) >= 6 or part.isdigit())
        for index, part in enumerate(fragmentParts)
    )


def _knownAtsUrl(readUrl: str) -> bool:
    host = readUrl.split("/", 1)[0].split(":", 1)[0]
    return any(host == suffix or host.endswith("." + suffix) for suffix in _ATS_HOST_SUFFIXES)


def _campaignSpecificAtsUrl(readUrl: str) -> bool:
    """Return true only when the ATS URL exposes a tenant or campaign identity."""
    host = readUrl.split("/", 1)[0].split(":", 1)[0]
    withoutFragment = readUrl.split("#", 1)[0]
    withoutQuery = withoutFragment.split("?", 1)[0]
    path = "/" + withoutQuery.split("/", 1)[1] if "/" in withoutQuery else "/"
    parts = [part.casefold() for part in path.split("/") if part]

    def tenantSubdomain(suffix: str) -> bool:
        prefix = host.removesuffix(suffix).rstrip(".")
        return bool(prefix and prefix not in {"www", "app", "jobs", "campus", "recruit"})

    if host.endswith(".zhiye.com") and tenantSubdomain("zhiye.com"):
        return True
    if host.endswith(".hotjob.cn") and tenantSubdomain("hotjob.cn"):
        return True
    if host == "mokahr.com" or host.endswith(".mokahr.com"):
        mokaParts = parts[1:] if parts[:1] == ["m"] else parts
        return len(mokaParts) >= 2 and mokaParts[0] in {
            "su", "campus_apply", "social_apply", "campus-recruitment",
            "social-recruitment", "recommendation-apply",
        }
    if host == "jobs.feishu.cn" or host.endswith(".jobs.feishu.cn"):
        if tenantSubdomain("jobs.feishu.cn"):
            return True
        return len(parts) >= 2 and parts[0] in {"s", "portal", "campus", "social"}
    return False


def applicationUrls(readJob: dict[str, Any]) -> frozenset[str]:
    source = canonicalUrl(readJob.get("sourceUrl"))
    values: set[str] = set()
    for field in _TEXT_FIELDS:
        text = str(readJob.get(field) or "")
        protocolMatches = list(_URL.finditer(text))
        bareMatches = [
            match for match in _BARE_URL.finditer(text)
            if not any(start.start() <= match.start() < start.end() for start in protocolMatches)
        ]
        for match in [*protocolMatches, *bareMatches]:
            token = canonicalUrl(match.group())
            clauseStart = max(
                (boundary.end() for boundary in _CLAUSE_BOUNDARY.finditer(text, 0, match.start())),
                default=0,
            )
            nextBoundary = _CLAUSE_BOUNDARY.search(text, match.end())
            clauseEnd = nextBoundary.start() if nextBoundary else len(text)
            context = text[clauseStart:clauseEnd]
            if token and (_APPLICATION_CUE.search(context) or _knownAtsUrl(token)):
                values.add(token)
    if source and _knownAtsUrl(source):
        values.add(source)
    return frozenset(values)


def applicationEmails(readJob: dict[str, Any]) -> frozenset[str]:
    values: set[str] = set()
    for field in _TEXT_FIELDS:
        values.update(match.casefold() for match in _EMAIL.findall(str(readJob.get(field) or "")))
    return frozenset(values)


def externalJobIds(readJob: dict[str, Any]) -> frozenset[str]:
    values = set()
    for field in (*_TEXT_FIELDS, "internalRemark"):
        text = unicodedata.normalize("NFKC", str(readJob.get(field) or ""))
        for cue in _EXTERNAL_JOB_ID.finditer(text):
            value = _EXTERNAL_JOB_ID_VALUE.match(text[cue.end():])
            if value:
                token = value.group(1).casefold()
                if token not in _EXTERNAL_JOB_ID_STOPWORDS:
                    values.add(token)
    return frozenset(values)


def campaignYears(readJob: dict[str, Any]) -> frozenset[int]:
    values = {int(match) for match in _YEAR.findall(str(readJob.get("reqGradYear") or ""))}
    for field in ("jobDesc", "reqSkills", "reqOther"):
        text = str(readJob.get(field) or "")
        for match in _YEAR.finditer(text):
            context = text[max(0, match.start() - 12):match.end() + 12]
            if re.search(r"届|毕业|应届|校招|校园招聘", context):
                values.add(int(match.group(1)))
    return frozenset(values)


def campaignSeasons(readJob: dict[str, Any]) -> frozenset[str]:
    text = " ".join(str(readJob.get(field) or "") for field in ("reqGradYear", "jobDesc", "reqOther"))
    seasons = set()
    if re.search(r"秋招|秋季招聘", text):
        seasons.add("AUTUMN")
    if re.search(r"春招|春季招聘", text):
        seasons.add("SPRING")
    if re.search(r"暑期|夏季招聘", text):
        seasons.add("SUMMER")
    if "补录" in text:
        seasons.add("SUPPLEMENT")
    return frozenset(seasons)


def jobCities(readJob: dict[str, Any]) -> frozenset[str]:
    rawValues: list[Any] = []
    cities = readJob.get("workCities")
    if isinstance(cities, (list, tuple, set)):
        rawValues.extend(cities)
    rawValues.append(readJob.get("workCity"))
    values: set[str] = set()
    for raw in rawValues:
        for part in _CITY_SEPARATOR.split(str(raw or "")):
            token = _NON_WORD.sub("", normalizeText(part))
            if token in _UNKNOWN_CITY_TOKENS:
                continue
            if _isNationalCoverageToken(token):
                token = "全国"
            if token.endswith("市") and len(token) > 1:
                token = token[:-1]
            if token:
                values.add(token)
    return frozenset(values)


def _isNationalCoverageToken(readToken: str) -> bool:
    return bool(
        readToken == "全国"
        or readToken.startswith(("全国各地", "全国范围"))
        or _NATIONAL_COVERAGE_CITY.search(readToken)
        or (
            readToken.startswith("全国")
            and "总部" not in readToken[2:]
            and any(
                cue in readToken[2:]
                for cue in ("就近", "自由选择", "含港澳台", "统一安排")
            )
        )
    )


def recruitFamily(readValue: Any) -> str:
    value = getattr(readValue, "name", readValue)
    if value in _OTHER_RECRUIT_VALUES:
        return ""
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = None
    return normalizeText(_RECRUIT_TYPE_NUMBERS.get(number, value))


def normalizedScalar(readValue: Any) -> str:
    if readValue in (None, ""):
        return ""
    return normalizeText(getattr(readValue, "name", readValue))


def normalizedDisplayScalar(readValue: Any) -> str:
    """Normalize formatting-only differences without erasing numeric meaning."""
    text = normalizedScalar(readValue).replace(" ", "")
    if text and not re.search(r"\d", text):
        text = _NON_WORD.sub("", text)
    elif text:
        text = _NUMERIC_RANGE_SEPARATOR.sub("-", text)
    return text


def workModeFamily(readValue: Any) -> str:
    if readValue in (None, ""):
        return ""
    value = getattr(readValue, "name", readValue)
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = None
    return normalizeText(_WORK_MODE_NUMBERS.get(number, value))


def normalizedRequirementSet(readJob: dict[str, Any], plural: str, singular: str) -> frozenset[str]:
    values = readJob.get(plural)
    rawValues = list(values) if isinstance(values, (list, tuple, set)) else []
    if not rawValues and readJob.get(singular) not in (None, ""):
        rawValues.append(readJob[singular])
    return frozenset(
        normalizeText(getattr(value, "name", value)) for value in rawValues if value not in (None, "")
    )


def majorKeys(readValue: Any) -> frozenset[str]:
    text = normalizeText(readValue)
    if not text:
        return frozenset()
    if "不限" in text:
        return frozenset({"<unrestricted>"})
    return frozenset(
        _NON_MAJOR_TOKEN.sub("", part)
        for part in _CITY_SEPARATOR.split(text)
        if _NON_MAJOR_TOKEN.sub("", part)
    )


def graduationEligibility(readValue: Any) -> frozenset[str]:
    text = normalizeText(readValue)
    if not text:
        return frozenset()
    values = set()
    if re.search(r"非\s*应届|往届|社会招聘|社招", text):
        values.add("NON_FRESH")
    withoutNegative = re.sub(r"非\s*应届", "", text)
    if "应届" in withoutNegative:
        values.add("FRESH")
    if re.search(r"实习|在校", text):
        values.add("INTERN")
    return frozenset(values)


def specificWorkLocation(readValue: Any) -> str:
    text = normalizeText(readValue)
    # A bare number is not an address: phrases such as “覆盖全国100多个城市”
    # describe a recruiting footprint and must not split otherwise identical
    # campaign reposts.  Concrete street/building/campus cues remain conflicts.
    token = _NON_WORD.sub("", text)
    if not text or _isNationalCoverageToken(token) or ("覆盖全国" in token and "城市" in token):
        return ""
    street = _STREET_ADDRESS_CUE.search(text) and not _NON_ADDRESS_STREET_WORD.search(text)
    regionalBuilding = _REGION_CUE.search(text) and _BUILDING_STAGE_CUE.search(text)
    return text if _STRONG_ADDRESS_CUE.search(text) or street or regionalBuilding else ""


def parseDate(readValue: Any) -> date | None:
    if isinstance(readValue, datetime):
        return readValue.date()
    if isinstance(readValue, date):
        return readValue
    try:
        return date.fromisoformat(str(readValue or "")[:10])
    except ValueError:
        return None


def plusOneMonth(readDate: date) -> date:
    monthIndex = readDate.month
    year = readDate.year + monthIndex // 12
    month = monthIndex % 12 + 1
    return date(year, month, min(readDate.day, monthrange(year, month)[1]))


def evidenceDeadline(readJob: dict[str, Any]) -> date | None:
    deadline = parseDate(readJob.get("applicationDeadline"))
    created = parseDate(readJob.get("createdAt"))
    if deadline and created and deadline == plusOneMonth(created):
        return None
    return deadline


def academicScope(readDate: date) -> int:
    return readDate.year + (1 if readDate.month >= 7 else 0)


def shanghaiToday() -> date:
    return datetime.now(ZoneInfo("Asia/Shanghai")).date()


def isRecycled(readStatus: Any) -> bool:
    name = getattr(readStatus, "name", readStatus)
    return name in _RECYCLED_VALUES


def isExpired(readStatus: Any) -> bool:
    name = getattr(readStatus, "name", readStatus)
    return name in _EXPIRED_VALUES


def publicationRank(readStatus: Any) -> int:
    name = getattr(readStatus, "name", readStatus)
    if name in {1, "1", "PUBLISHED"}:
        return 0
    if name in {0, "0", "OFFLINE", None, ""}:
        return 1
    if name in _EXPIRED_VALUES:
        return 2
    return 3 if name in _RECYCLED_VALUES else 1


def truthyFlag(readValue: Any) -> bool:
    return readValue is True or normalizeText(readValue) in {"1", "true", "yes"}


@dataclass(frozen=True)
class JobIdentity:
    exactKey: tuple[str, str, str]
    positionKey: str
    aliases: frozenset[str]
    urls: frozenset[str]
    jobUrls: frozenset[str]
    externalJobIds: frozenset[str]
    emails: frozenset[str]
    years: frozenset[int]
    scopeYears: frozenset[int]
    seasons: frozenset[str]
    cities: frozenset[str]
    departmentKey: str
    recruitFamily: str
    headcountKey: str
    headcountDisplayKey: str
    salaryMinKey: str
    salaryMaxKey: str
    salaryDisplayKey: str
    workModeKey: str
    majorKeys: frozenset[str]
    educationKeys: frozenset[str]
    eligibilityKeys: frozenset[str]
    workLocationKey: str
    workStartDate: date | None
    workEndDate: date | None
    workDaysPerWeekKey: str
    workDurationTypeKey: str
    workPeriodTypeKey: str
    workProvinceKey: str
    deadline: date | None
    recycled: bool
    expired: bool
    job: dict[str, Any]

    @classmethod
    def fromJob(cls, readJob: dict[str, Any]) -> JobIdentity:
        years = campaignYears(readJob)
        urls = applicationUrls(readJob)
        observed = parseDate(readJob.get("createdAt")) or shanghaiToday()
        return cls(
            exactKey=(
                canonicalUrl(readJob.get("sourceUrl")),
                normalizeText(readJob.get("companyName")),
                normalizePosition(readJob.get("positionName")),
            ),
            positionKey=normalizePosition(readJob.get("positionName")),
            aliases=companyAliases(readJob.get("companyName")),
            urls=urls,
            jobUrls=frozenset(url for url in urls if _jobSpecificApplicationUrl(url)),
            externalJobIds=externalJobIds(readJob),
            emails=applicationEmails(readJob),
            years=years,
            scopeYears=years or frozenset({academicScope(observed)}),
            seasons=campaignSeasons(readJob),
            cities=jobCities(readJob),
            departmentKey=normalizeText(readJob.get("department")),
            recruitFamily=recruitFamily(readJob.get("recruitType")),
            headcountKey=normalizedScalar(readJob.get("headcount")),
            headcountDisplayKey=normalizedDisplayScalar(readJob.get("headcountDisplay")),
            salaryMinKey=normalizedScalar(readJob.get("salaryMin")),
            salaryMaxKey=normalizedScalar(readJob.get("salaryMax")),
            salaryDisplayKey=normalizedDisplayScalar(readJob.get("salaryDisplay")),
            workModeKey=workModeFamily(readJob.get("workMode")),
            majorKeys=majorKeys(readJob.get("reqMajor")),
            educationKeys=normalizedRequirementSet(readJob, "reqEduLevels", "reqEduLevel"),
            eligibilityKeys=graduationEligibility(readJob.get("reqGradYear")),
            workLocationKey=specificWorkLocation(readJob.get("workLocation")),
            workStartDate=parseDate(readJob.get("workStartDate")),
            workEndDate=parseDate(readJob.get("workEndDate")),
            workDaysPerWeekKey=normalizedScalar(readJob.get("workDaysPerWeek")),
            workDurationTypeKey=normalizedScalar(readJob.get("workDurationType")),
            workPeriodTypeKey=normalizedScalar(readJob.get("workPeriodType")),
            workProvinceKey=normalizedScalar(readJob.get("workProvince")),
            deadline=evidenceDeadline(readJob),
            recycled=isRecycled(readJob.get("status")),
            expired=isExpired(readJob.get("status")),
            job=readJob,
        )


def _dedupOrderKey(readJob: dict[str, Any]) -> tuple[Any, ...]:
    """Put evidence-rich records first so greedy complete-link clustering is stable."""
    identity = JobIdentity.fromJob(readJob)
    setEvidence = (
        identity.jobUrls, identity.externalJobIds, identity.years, identity.seasons, identity.cities,
        identity.majorKeys, identity.educationKeys, identity.eligibilityKeys,
    )
    scalarEvidence = (
        identity.departmentKey, identity.recruitFamily, identity.headcountKey,
        identity.headcountDisplayKey, identity.salaryMinKey, identity.salaryMaxKey,
        identity.salaryDisplayKey, identity.workModeKey, identity.workLocationKey,
        identity.workStartDate, identity.workEndDate, identity.workDaysPerWeekKey,
        identity.workDurationTypeKey, identity.workPeriodTypeKey, identity.workProvinceKey,
        identity.deadline,
    )
    specificity = sum(bool(value) for value in setEvidence + scalarEvidence)
    textLength = sum(len(str(readJob.get(field) or "")) for field in _TEXT_FIELDS)
    sourceIsApplication = bool(identity.exactKey[0] and identity.exactKey[0] in identity.urls)
    identifier = str(readJob.get("id") or "")
    identifierKey: tuple[int, Any] = (
        (0, int(identifier)) if identifier.isdigit() else (1, identifier)
    )
    return (
        publicationRank(readJob.get("status")),
        -int(sourceIsApplication),
        -specificity,
        -textLength,
        identifierKey,
        identity.exactKey,
        tuple(sorted(identity.urls)),
        tuple(sorted(identity.cities)),
        tuple(sorted(identity.years)),
        tuple(sorted(identity.seasons)),
    )


@dataclass(frozen=True)
class JobDuplicateMatch:
    reason: str
    winner: dict[str, Any]
    requiresPersistedUpdate: bool = False


def _campaignCompatible(
    first: JobIdentity, second: JobIdentity, *, compareWorkLocation: bool = True,
) -> bool:
    if first.scopeYears.isdisjoint(second.scopeYears):
        return False
    if first.years and second.years and first.years != second.years:
        return False
    if first.seasons and second.seasons and first.seasons != second.seasons:
        return False
    if first.jobUrls and second.jobUrls and first.jobUrls != second.jobUrls:
        return False
    if first.externalJobIds and second.externalJobIds \
            and first.externalJobIds != second.externalJobIds:
        return False
    if first.departmentKey and second.departmentKey and first.departmentKey != second.departmentKey:
        return False
    if first.recruitFamily and second.recruitFamily and first.recruitFamily != second.recruitFamily:
        return False
    for firstValue, secondValue in (
        (first.headcountKey, second.headcountKey),
        (first.headcountDisplayKey, second.headcountDisplayKey),
        (first.salaryMinKey, second.salaryMinKey),
        (first.salaryMaxKey, second.salaryMaxKey),
        (first.salaryDisplayKey, second.salaryDisplayKey),
        (first.workModeKey, second.workModeKey),
        (first.workStartDate, second.workStartDate),
        (first.workEndDate, second.workEndDate),
        (first.workDaysPerWeekKey, second.workDaysPerWeekKey),
        (first.workDurationTypeKey, second.workDurationTypeKey),
        (first.workPeriodTypeKey, second.workPeriodTypeKey),
        (first.workProvinceKey, second.workProvinceKey),
    ):
        if firstValue and secondValue and firstValue != secondValue:
            return False
    if first.cities and second.cities and first.cities.isdisjoint(second.cities):
        return False
    if first.majorKeys and second.majorKeys and first.majorKeys != second.majorKeys:
        return False
    if first.educationKeys and second.educationKeys and first.educationKeys != second.educationKeys:
        return False
    if (
        first.eligibilityKeys
        and second.eligibilityKeys
        and (
            ("FRESH" in first.eligibilityKeys and "NON_FRESH" in second.eligibilityKeys)
            or ("NON_FRESH" in first.eligibilityKeys and "FRESH" in second.eligibilityKeys)
            or first.eligibilityKeys.isdisjoint(second.eligibilityKeys)
        )
    ):
        return False
    if compareWorkLocation and first.workLocationKey and second.workLocationKey \
            and first.workLocationKey != second.workLocationKey:
        return False
    return not (
        first.deadline
        and second.deadline
        and abs((first.deadline - second.deadline).days) > 183
    )


class JobDuplicateIndex:
    """Incremental index for exact and high-confidence cross-source duplicates."""

    def __init__(self, readJobs: Iterable[dict[str, Any]] = (), *, crossSource: bool = True) -> None:
        self.crossSource = crossSource
        self._exact: dict[tuple[str, str, str], list[JobIdentity]] = {}
        self._urls: dict[tuple[str, str], list[JobIdentity]] = {}
        self._clusters: dict[int, list[JobIdentity]] = {}
        self._persistedClusters: set[int] = set()
        self._rootsByPersistedId: dict[str, dict[str, Any]] = {}
        self._syntheticRoots: set[int] = set()
        jobs = list(readJobs)
        evidenceJobs = [job for job in jobs if self._evidenceReference(job)]
        self._syntheticPersistedIds = {
            reference[0]
            for job in evidenceJobs
            if (reference := self._evidenceReference(job)) and reference[1] == "keeper-before"
        }
        regularJobs = [job for job in jobs if not self._evidenceReference(job)]
        for job in sorted(regularJobs, key=_dedupOrderKey):
            match = self.find(job)
            if match is None:
                self.add(job)
                root = job
            else:
                # Reconstruct complete-link history for preloaded database rows
                # without mutating the persisted keeper fields in memory.
                self._recordClusterMember(match, job, persisted=self._defaultPersisted(job))
                root = match.winner
            if job.get("id") not in (None, ""):
                self._rootsByPersistedId[str(job["id"])] = root
        detachedKeepers = set()
        for job in evidenceJobs:
            reference = self._evidenceReference(job)
            if not reference or reference[1] != "keeper-before":
                continue
            root = self._rootsByPersistedId.get(reference[0])
            if root is not None and not self._sameCanonicalLineage(root, job):
                detachedKeepers.add(reference[0])
        # Java stores every successfully merged incoming record as a hidden
        # evidence row. Reattach it to the exact keeper so a restart retains
        # complete-link boundaries instead of treating a union as one record.
        pending = sorted(evidenceJobs, key=_dedupOrderKey)
        while pending:
            deferred = []
            progressed = False
            for job in pending:
                keeperId, role = self._evidenceReference(job) or ("", "")
                if keeperId in detachedKeepers:
                    self.add(job, persisted=True)
                    progressed = True
                    continue
                root = self._rootsByPersistedId.get(keeperId)
                if root is None:
                    deferred.append(job)
                    continue
                if role == "legacy-dedup" and not self._compatibleLegacyLineage(root, job):
                    self.add(job, persisted=True)
                    progressed = True
                    continue
                match = JobDuplicateMatch("persisted-merge-evidence", root)
                self._recordClusterMember(match, job, persisted=True)
                if role == "keeper-before":
                    self._syntheticRoots.add(id(root))
                if job.get("id") not in (None, ""):
                    self._rootsByPersistedId[str(job["id"])] = root
                progressed = True
            if not progressed:
                # Preserve unresolved/orphaned audit rows as recycled-only
                # clusters; they cannot suppress active jobs but remain visible
                # to future reconstruction if their keeper is restored.
                for job in deferred:
                    self.add(job, persisted=True)
                break
            pending = deferred

    @staticmethod
    def _evidenceReference(readJob: dict[str, Any]) -> tuple[str, str] | None:
        if not isRecycled(readJob.get("status")):
            return None
        reason = str(readJob.get("recycleReason") or "")
        match = _PERSISTED_EVIDENCE.match(reason)
        if match:
            return match.group(1), match.group(2)
        match = _TRUSTED_MANUAL_MERGE_EVIDENCE.match(reason)
        if match:
            return match.group(1), "manual-merge"
        match = _TRUSTED_R2_EVIDENCE.match(reason)
        return (match.group(1), "legacy-dedup") if match else None

    @staticmethod
    def _evidenceKeeperId(readJob: dict[str, Any]) -> str:
        reference = JobDuplicateIndex._evidenceReference(readJob)
        return reference[0] if reference else ""

    @staticmethod
    def _sameCanonicalLineage(
        readCurrent: dict[str, Any], readOriginal: dict[str, Any],
    ) -> bool:
        current = JobIdentity.fromJob(readCurrent)
        original = JobIdentity.fromJob(readOriginal)
        originalLocation = normalizeText(readOriginal.get("workLocation"))
        currentLocation = normalizeText(readCurrent.get("workLocation"))
        return bool(
            current.positionKey
            and current.positionKey == original.positionKey
            and current.aliases & original.aliases
            and _campaignCompatible(current, original, compareWorkLocation=False)
            and (not originalLocation or originalLocation in currentLocation)
        )

    @staticmethod
    def _compatibleLegacyLineage(
        readKeeper: dict[str, Any], readLoser: dict[str, Any],
    ) -> bool:
        keeper = JobIdentity.fromJob(readKeeper)
        loser = JobIdentity.fromJob(readLoser)
        return bool(
            keeper.positionKey
            and keeper.positionKey == loser.positionKey
            and keeper.aliases & loser.aliases
            and _campaignCompatible(keeper, loser)
        )

    @staticmethod
    def _defaultPersisted(readJob: dict[str, Any]) -> bool:
        return readJob.get("id") not in (None, "")

    def add(self, readJob: dict[str, Any], *, persisted: bool | None = None) -> None:
        identity = JobIdentity.fromJob(readJob)
        if not identity.positionKey:
            return
        self._clusters[id(readJob)] = [identity]
        isPersisted = self._defaultPersisted(readJob) if persisted is None else persisted
        if isPersisted:
            self._persistedClusters.add(id(readJob))
        if readJob.get("id") not in (None, ""):
            self._rootsByPersistedId[str(readJob["id"])] = readJob
        if identity.exactKey[0]:
            self._exact.setdefault(identity.exactKey, []).append(identity)
        if not self.crossSource:
            return
        for value in identity.urls:
            self._urls.setdefault((identity.positionKey, value), []).append(identity)

    def find(
        self, readJob: dict[str, Any], *, allowPersistedUpdate: bool = False,
    ) -> JobDuplicateMatch | None:
        incoming = JobIdentity.fromJob(readJob)
        if not incoming.positionKey:
            return None
        exact = self._exact.get(incoming.exactKey, []) if incoming.exactKey[0] else []
        for candidate in exact:
            if incoming.recycled != candidate.recycled or incoming.expired != candidate.expired:
                continue
            if self._clusterCompatible(incoming, candidate):
                requiresUpdate = self._wouldDiscardPersistentEvidence(incoming, candidate)
                if allowPersistedUpdate or not requiresUpdate:
                    return JobDuplicateMatch(
                        "same-source-company-position", candidate.job, requiresUpdate,
                    )
        if not self.crossSource:
            return None

        for url in sorted(incoming.urls):
            comparable: list[JobIdentity] = []
            seen: set[int] = set()
            for candidate in self._urls.get((incoming.positionKey, url), []):
                marker = id(candidate)
                if marker in seen:
                    continue
                seen.add(marker)
                if incoming.recycled != candidate.recycled or incoming.expired != candidate.expired:
                    continue
                if not self._anchorMembers(incoming, candidate, url):
                    continue
                comparable.append(candidate)
            compatible: list[tuple[JobIdentity, bool]] = []
            for candidate in comparable:
                if not self._clusterCompatible(incoming, candidate):
                    continue
                requiresUpdate = self._wouldDiscardPersistentEvidence(incoming, candidate)
                if allowPersistedUpdate or not requiresUpdate:
                    compatible.append((candidate, requiresUpdate))
            # An incompatible sibling cluster must not veto a direct match with
            # a complete-link-compatible cluster.  Choosing one compatible
            # cluster never joins the siblings: recordDuplicate() only appends
            # the incoming identity to that winner's clique, so later conflict
            # checks still see every known member and cannot bridge them.
            if not compatible:
                continue
            if _jobSpecificApplicationUrl(url):
                candidate, requiresUpdate = compatible[0]
                return JobDuplicateMatch(
                    "same-job-application-url-company-position", candidate.job, requiresUpdate,
                )
            emailCandidate = next(
                (
                    (candidate, requiresUpdate)
                    for candidate, requiresUpdate in compatible
                    if any(
                        incoming.emails & member.emails
                        for member in self._anchorMembers(incoming, candidate, url)
                    )
                ),
                None,
            )
            if emailCandidate is not None:
                candidate, requiresUpdate = emailCandidate
                return JobDuplicateMatch(
                    "same-campaign-url-email-company-position", candidate.job, requiresUpdate,
                )
            # University mirrors frequently omit the mailbox while retaining the
            # employer's tenant/campaign ATS route.  Within the same guarded
            # academic scope, an explicit shared graduation year or the same
            # non-empty recruiting family supplies the missing campaign evidence.
            # Bare ATS provider home pages remain insufficient.
            if _campaignSpecificAtsUrl(url):
                for candidate, requiresUpdate in compatible:
                    members = self._anchorMembers(incoming, candidate, url)
                    sameExplicitYear = any(
                        bool(incoming.years & member.years) for member in members
                    )
                    sameRecruitFamily = any(
                        bool(
                            incoming.recruitFamily
                            and incoming.recruitFamily == member.recruitFamily
                        )
                        for member in members
                    )
                    if sameExplicitYear or sameRecruitFamily:
                        return JobDuplicateMatch(
                            "same-ats-campaign-url-cycle-company-position",
                            candidate.job,
                            requiresUpdate,
                        )

        # Email alone is not an automatic match: a long-lived HR mailbox may be
        # reused for different openings or recruiting cycles. It only strengthens
        # a shared application URL above.
        return None

    def _clusterCompatible(self, incoming: JobIdentity, candidate: JobIdentity) -> bool:
        members = self._clusterMembers(candidate)
        if not all(_campaignCompatible(incoming, member) for member in members):
            return False
        if id(candidate.job) in self._persistedClusters:
            # A persisted multi-city union must not become a transitive bridge
            # after restart. Only nested/equal sets are safe to merge; original
            # member rows (when present) are still checked above.
            for member in members:
                if incoming.cities and member.cities and not (
                    incoming.cities.issubset(member.cities)
                    or member.cities.issubset(incoming.cities)
                ):
                    return False
        return True

    def _clusterMembers(self, candidate: JobIdentity) -> list[JobIdentity]:
        members = self._clusters.get(id(candidate.job), [candidate])
        originals = [
            member for member in members
            if str(member.job.get("id") or "") not in self._syntheticPersistedIds
        ]
        if originals:
            return originals
        if id(candidate.job) in self._syntheticRoots and len(members) > 1:
            return members[1:]
        return members

    def _anchorMembers(
        self, incoming: JobIdentity, candidate: JobIdentity, url: str,
    ) -> list[JobIdentity]:
        """Keep URL/company/strengthening evidence on the same original row."""
        return [
            member for member in self._clusterMembers(candidate)
            if url in member.urls and incoming.aliases & member.aliases
        ]

    def _wouldDiscardPersistentEvidence(
        self, incoming: JobIdentity, candidate: JobIdentity,
    ) -> bool:
        """Keep evidence that an existing DB keeper cannot persist by merging in memory."""
        if id(candidate.job) not in self._persistedClusters:
            return False
        members = self._clusterMembers(candidate)
        if any(
            bool(getattr(incoming, field))
            and not set(getattr(incoming, field)).issubset(
                set().union(*(set(getattr(member, field)) for member in members))
            )
            for field in _PERSISTED_SET_HARD_EVIDENCE
        ):
            return True
        memberDeadlines = [member.deadline for member in members if member.deadline]
        if incoming.deadline and (
            not memberDeadlines or incoming.deadline > max(memberDeadlines)
        ):
            return True
        if truthyFlag(incoming.job.get("recommended")) and not any(
            truthyFlag(member.job.get("recommended")) for member in members
        ):
            return True
        return any(
            bool(getattr(incoming, field))
            and not any(bool(getattr(member, field)) for member in members)
            for field in _PERSISTED_SCALAR_HARD_EVIDENCE
        )

    def recordDuplicate(
        self, readMatch: JobDuplicateMatch, readDuplicate: dict[str, Any],
        *, persisted: bool | None = None,
    ) -> None:
        self._recordClusterMember(readMatch, readDuplicate, persisted=persisted)
        mergeDuplicateFields(readMatch.winner, readDuplicate)

    def _recordClusterMember(
        self, readMatch: JobDuplicateMatch, readDuplicate: dict[str, Any],
        *, persisted: bool | None = None,
    ) -> None:
        members = self._clusters.setdefault(id(readMatch.winner), [])
        identity = JobIdentity.fromJob(readDuplicate)
        root = members[0]
        members.append(identity)
        isPersisted = self._defaultPersisted(readDuplicate) if persisted is None else persisted
        if isPersisted:
            self._persistedClusters.add(id(readMatch.winner))
        if readDuplicate.get("id") not in (None, ""):
            self._rootsByPersistedId[str(readDuplicate["id"])] = readMatch.winner

        def indexRoot(index: dict[Any, list[JobIdentity]], key: Any) -> None:
            values = index.setdefault(key, [])
            if all(value is not root for value in values):
                values.append(root)

        if identity.exactKey[0]:
            indexRoot(self._exact, identity.exactKey)
        if self.crossSource:
            for value in identity.urls:
                indexRoot(self._urls, (identity.positionKey, value))

    def addIfUnique(self, readJob: dict[str, Any]) -> JobDuplicateMatch | None:
        match = self.find(readJob)
        if match is None:
            self.add(readJob)
        return match


def deduplicateJobRecords(readJobs: Iterable[dict[str, Any]], *, crossSource: bool = True) -> list[dict[str, Any]]:
    index = JobDuplicateIndex(crossSource=crossSource)
    jobs = list(readJobs)
    unique = []
    for job in sorted(jobs, key=_dedupOrderKey):
        match = index.addIfUnique(job)
        if match is None:
            unique.append(job)
        else:
            index.recordDuplicate(match, job)
    survivorIds = {id(job) for job in unique}
    return [job for job in jobs if id(job) in survivorIds]


def mergeDuplicateFields(updateKeeper: dict[str, Any], readDuplicate: dict[str, Any]) -> None:
    """Conservatively retain richer non-conflicting fields on an in-memory keeper."""
    mergedCities = sorted(jobCities(updateKeeper) | jobCities(readDuplicate))
    if mergedCities:
        updateKeeper["workCities"] = mergedCities
    for field in (
        "department", "workCity", "workProvince", "workLocation", "reqGradYear", "reqMajor",
        "reqEduLevel", "reqEduLevels", "headcount", "headcountDisplay", "salaryMin", "salaryMax",
        "salaryDisplay", "workMode", "workStartDate", "workEndDate", "workDaysPerWeek",
        "workDurationType", "workPeriodType", "jobSubCategory",
    ):
        if updateKeeper.get(field) in (None, "", []) and readDuplicate.get(field) not in (None, "", []):
            updateKeeper[field] = readDuplicate[field]
    for field in ("jobDesc", "reqSkills", "reqOther"):
        keeperText = str(updateKeeper.get(field) or "")
        duplicateText = str(readDuplicate.get(field) or "")
        if duplicateText and (
            not keeperText
            or (
                normalizeText(keeperText) in normalizeText(duplicateText)
                and len(duplicateText) > len(keeperText)
            )
        ):
            updateKeeper[field] = readDuplicate[field]
    keeperDeadline = parseDate(updateKeeper.get("applicationDeadline"))
    duplicateDeadline = parseDate(readDuplicate.get("applicationDeadline"))
    if duplicateDeadline and (not keeperDeadline or duplicateDeadline > keeperDeadline):
        updateKeeper["applicationDeadline"] = readDuplicate["applicationDeadline"]
    if truthyFlag(readDuplicate.get("recommended")):
        updateKeeper["recommended"] = True
