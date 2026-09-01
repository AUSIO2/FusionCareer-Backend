"""Normalize model job output to Java JobPostRequest fields."""

from __future__ import annotations

import re
from typing import Any


JOB_FIELDS = (
    "sourceType", "sourceUrl", "companyName", "department", "positionName",
    "jobCategory", "jobSubCategory", "recruitType", "headcount",
    "workStartDate", "workEndDate", "workDaysPerWeek", "workDurationType",
    "workPeriodType", "workMode", "workCity", "workProvince", "workLocation",
    "salaryMin", "salaryMax", "salaryDisplay", "jobDesc", "reqEduLevel",
    "reqMajor", "reqGradYear", "reqSkills", "reqOther", "recommended", "status",
)
ZH_FIELDS = {
    "来源类型": "sourceType", "来源链接": "sourceUrl", "单位名称": "companyName",
    "部门": "department", "岗位名称": "positionName", "岗位大类": "jobCategory",
    "岗位二级分类": "jobSubCategory", "招聘类型": "recruitType", "招聘人数": "headcount",
    "工作开始日": "workStartDate", "工作结束日": "workEndDate",
    "每周工作天数": "workDaysPerWeek", "每周工作天数类型": "workDurationType",
    "实习总时长类型": "workPeriodType", "工作形式": "workMode", "工作城市": "workCity",
    "工作省份": "workProvince", "工作地点原文": "workLocation", "薪资下限": "salaryMin",
    "薪资上限": "salaryMax", "薪资展示": "salaryDisplay", "岗位描述": "jobDesc",
    "学历要求": "reqEduLevel", "专业要求": "reqMajor", "届别要求": "reqGradYear",
    "技能要求": "reqSkills", "其他要求与投递说明": "reqOther",
}
ENUM_MAPS = {
    "sourceType": {"平台发布": "PLATFORM", "就业资讯源爬取": "CRAWL"},
    "jobCategory": {
        "学术教职": "ACADEMIC", "党政机关": "GOVERNMENT", "新闻媒体": "MEDIA",
        "企业公司": "ENTERPRISE", "企业": "ENTERPRISE", "其他": "OTHER",
    },
    "jobSubCategory": {
        "升学深造": "FURTHER_STUDY", "考取教职": "TEACHING_POSITION",
        "中学教师": "MIDDLE_SCHOOL_TEACHER", "选调生": "SELECTED_GRADUATE",
        "公务员": "CIVIL_SERVANT", "高校行政": "UNIVERSITY_ADMIN", "医院": "HOSPITAL",
        "银行": "BANK", "其他事业单位": "OTHER_PUBLIC_INSTITUTION",
        "党报央媒": "CENTRAL_MEDIA", "地区主流媒体": "REGIONAL_MEDIA",
        "其他媒体机构": "OTHER_MEDIA", "自媒体": "SELF_MEDIA", "国央企": "STATE_OWNED",
        "民企": "PRIVATE_ENTERPRISE", "外企": "FOREIGN_ENTERPRISE", "其他": "OTHER",
    },
    "recruitType": {
        "大实习": "BIG_INTERNSHIP", "小实习": "SMALL_INTERNSHIP",
        "日常实习": "DAILY_INTERNSHIP", "应届生招聘": "CAMPUS_RECRUITMENT",
        "应届生摸排": "CAMPUS_SCREENING", "其他": "OTHER",
    },
    "workDurationType": {
        "一周1-2天": "ONE_TO_TWO_DAYS", "一周3-4天": "THREE_TO_FOUR_DAYS",
        "一周5天": "FIVE_DAYS",
    },
    "workPeriodType": {
        "3个月以内": "LESS_THAN_THREE_MONTHS", "3-6个月": "THREE_TO_SIX_MONTHS",
        "6个月以上": "MORE_THAN_SIX_MONTHS",
    },
    "workMode": {"线上": "ONLINE", "线下": "OFFLINE", "线上线下均可": "HYBRID"},
    "reqEduLevel": {
        "本科生": "UNDERGRADUATE", "学术硕士研究生": "ACADEMIC_MASTER",
        "硕士研究生": "ACADEMIC_MASTER", "专业硕士研究生": "PROFESSIONAL_MASTER",
        "博士研究生": "DOCTORAL",
    },
}
SUB_PARENTS = {
    "FURTHER_STUDY": "ACADEMIC", "TEACHING_POSITION": "ACADEMIC",
    "MIDDLE_SCHOOL_TEACHER": "ACADEMIC", "SELECTED_GRADUATE": "GOVERNMENT",
    "CIVIL_SERVANT": "GOVERNMENT", "UNIVERSITY_ADMIN": "GOVERNMENT",
    "HOSPITAL": "GOVERNMENT", "BANK": "GOVERNMENT",
    "OTHER_PUBLIC_INSTITUTION": "GOVERNMENT", "CENTRAL_MEDIA": "MEDIA",
    "REGIONAL_MEDIA": "MEDIA", "OTHER_MEDIA": "MEDIA", "SELF_MEDIA": "MEDIA",
    "STATE_OWNED": "ENTERPRISE", "PRIVATE_ENTERPRISE": "ENTERPRISE",
    "FOREIGN_ENTERPRISE": "ENTERPRISE",
}
INT_FIELDS = {"headcount", "workDaysPerWeek", "salaryMin", "salaryMax"}
DATE_FIELDS = {"workStartDate", "workEndDate"}


def normalizeText(readValue: Any) -> str:
    if readValue is None:
        return ""
    return re.sub(r"\s+", " ", str(readValue).strip())


def normalizeJob(
    readJob: dict[str, Any],
    readSourceUrl: str,
    readSourceType: str,
) -> tuple[dict[str, Any], list[str]]:
    readFlat: dict[str, Any] = {}
    for readKey, readValue in readJob.items():
        updateKey = ZH_FIELDS.get(readKey, readKey)
        if updateKey in JOB_FIELDS and readValue not in (None, "", []):
            readFlat[updateKey] = readValue

    createJob: dict[str, Any] = {}
    for readField in JOB_FIELDS:
        readValue = readFlat.get(readField)
        if readField in ENUM_MAPS:
            readText = normalizeText(readValue)
            createJob[readField] = ENUM_MAPS[readField].get(readText, readText or None)
        elif readField in INT_FIELDS:
            try:
                createJob[readField] = int(readValue) if readValue not in (None, "") else None
            except (TypeError, ValueError):
                createJob[readField] = None
        elif readField in DATE_FIELDS:
            readText = normalizeText(readValue)
            createJob[readField] = readText if re.fullmatch(r"\d{4}-\d{2}-\d{2}", readText) else None
        elif readField == "recommended":
            createJob[readField] = bool(readValue) if readValue is not None else False
        else:
            createJob[readField] = normalizeText(readValue) or None

    createJob["sourceType"] = readSourceType
    createJob["sourceUrl"] = readSourceUrl or createJob.get("sourceUrl")
    createJob["status"] = "OFFLINE"
    createJob["jobCategory"] = createJob.get("jobCategory") or "OTHER"
    createJob["jobSubCategory"] = createJob.get("jobSubCategory") or "OTHER"
    createJob["recruitType"] = createJob.get("recruitType") or "OTHER"

    readSub = createJob.get("jobSubCategory")
    if readSub in SUB_PARENTS:
        createJob["jobCategory"] = SUB_PARENTS[readSub]
    elif createJob["jobCategory"] == "OTHER":
        readCompany = createJob.get("companyName") or ""
        if re.search(r"(有限公司|股份公司|科技公司)", readCompany):
            createJob["jobCategory"] = "ENTERPRISE"
            createJob["jobSubCategory"] = "PRIVATE_ENTERPRISE"

    readWarnings = []
    for readField in ("companyName", "positionName"):
        if not createJob.get(readField):
            readWarnings.append(f"missing {readField}")
    createJob = {
        readField: readValue for readField, readValue in createJob.items()
        if readValue is not None and not (readField == "recommended" and readValue is False)
    }
    return createJob, readWarnings


def deduplicateJobs(readJobs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    readKeys: set[tuple[str, str, str]] = set()
    readUnique = []
    for readJob in readJobs:
        readKey = (
            normalizeText(readJob.get("sourceUrl")).casefold(),
            normalizeText(readJob.get("companyName")).casefold(),
            normalizeText(readJob.get("positionName")).casefold(),
        )
        if readKey in readKeys:
            continue
        readKeys.add(readKey)
        readUnique.append(readJob)
    return readUnique
