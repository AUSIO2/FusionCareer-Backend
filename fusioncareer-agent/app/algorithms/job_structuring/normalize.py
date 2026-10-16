"""Normalize model job output to Java JobPostRequest fields."""

from __future__ import annotations

import re
from datetime import date
from typing import Any

from app.algorithms.job_record_dedup import deduplicateJobRecords
from app.config import settings


JOB_FIELDS = (
    "sourceType", "sourceUrl", "companyName", "department", "positionName",
    "jobCategory", "jobSubCategory", "recruitType", "headcount", "headcountDisplay",
    "workStartDate", "workEndDate", "applicationDeadline", "workDaysPerWeek", "workTimeRequirement",
    "workDurationType", "workPeriodType", "workMode", "workCity", "workCities", "workProvince", "workLocation",
    "salaryMin", "salaryMax", "salaryDisplay", "jobDesc", "reqEduLevel",
    "reqEduLevels", "reqMajor", "reqGradYear", "reqSkills", "reqOther", "careerDirection",
    "recommended", "status",
)
ZH_FIELDS = {
    "来源类型": "sourceType", "来源链接": "sourceUrl", "单位名称": "companyName",
    "部门": "department", "岗位名称": "positionName", "岗位大类": "jobCategory",
    "岗位二级分类": "jobSubCategory", "招聘类型": "recruitType", "招聘人数": "headcount",
    "工作开始日": "workStartDate", "工作结束日": "workEndDate",
    "投递截止日": "applicationDeadline",
    "投递截止日期": "applicationDeadline", "applyDeadline": "applicationDeadline",
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
        "本科及以上": "UNDERGRADUATE",
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
DATE_FIELDS = {"workStartDate", "workEndDate", "applicationDeadline"}
TEXT_LIMITS = {
    "sourceUrl": 512,
    "companyName": 128,
    "department": 128,
    "positionName": 128,
    "workCity": 32,
    "workProvince": 32,
    "workLocation": 128,
    "salaryDisplay": 64,
    "headcountDisplay": 64,
    "reqMajor": 256,
    "reqGradYear": 16,
    "reqSkills": 512,
    "workTimeRequirement": 2048,
    "careerDirection": 2048,
    # req_other is TEXT in Java/MySQL.  Keeping only 512 characters could cut
    # off the application URL/email that identifies a cross-source repost.
    "reqOther": 8192,
}


def normalizeText(readValue: Any) -> str:
    if readValue is None:
        return ""
    return re.sub(r"\s+", " ", str(readValue).strip())


def normalizeKey(readKey: str) -> str:
    return ZH_FIELDS.get(
        readKey,
        re.sub(r"_([a-z])", lambda readMatch: readMatch.group(1).upper(), readKey),
    )


def normalizeDate(readValue: Any) -> str:
    readText = normalizeText(readValue)
    try:
        date.fromisoformat(readText)
    except ValueError:
        return ""
    return readText


def normalizeJob(
    readJob: dict[str, Any],
    readSourceUrl: str,
    readSourceType: str,
) -> tuple[dict[str, Any], list[str]]:
    readFlat: dict[str, Any] = {}
    readWarnings = []
    for readKey, readValue in readJob.items():
        updateKey = normalizeKey(readKey)
        if updateKey == "sourceType":
            continue  # Source type is supplied by the caller, not extracted from article text.
        if updateKey in JOB_FIELDS and readValue not in (None, "", []):
            if updateKey in readFlat:
                readWarnings.append(f"duplicate {updateKey}")
                continue
            readFlat[updateKey] = readValue

    readFlat["sourceType"] = readSourceType
    createJob: dict[str, Any] = {}
    for readField in JOB_FIELDS:
        readValue = readFlat.get(readField)
        if readField in ENUM_MAPS:
            readText = normalizeText(readValue)
            readEnum = ENUM_MAPS[readField]
            if not readText:
                createJob[readField] = None
            elif readText in readEnum.values():
                createJob[readField] = readText
            elif readText in readEnum:
                createJob[readField] = readEnum[readText]
            else:
                createJob[readField] = None
                readWarnings.append(f"invalid {readField}: {readText}")
        elif readField in INT_FIELDS:
            if readValue in (None, ""):
                createJob[readField] = None
            elif type(readValue) is int or (
                    isinstance(readValue, str) and re.fullmatch(r"-?\d+", readValue.strip())):
                createJob[readField] = int(readValue)
            else:
                createJob[readField] = None
                readWarnings.append(f"invalid {readField}: {readValue}")
        elif readField in DATE_FIELDS:
            readText = normalizeText(readValue)
            createJob[readField] = normalizeDate(readText) or None
            if readText and createJob[readField] is None:
                readWarnings.append(f"invalid {readField}: {readText}")
        elif readField == "workCities":
            values = readValue if isinstance(readValue, list) else [readValue]
            createJob[readField] = list(dict.fromkeys(
                normalizeText(value)[:TEXT_LIMITS["workCity"]]
                for value in values if normalizeText(value)
            )) or None
        elif readField == "reqEduLevels":
            values = readValue if isinstance(readValue, list) else [readValue]
            mapped = []
            for value in values:
                text = normalizeText(value)
                if text in ENUM_MAPS["reqEduLevel"].values():
                    mapped.append(text)
                elif text in ENUM_MAPS["reqEduLevel"]:
                    mapped.append(ENUM_MAPS["reqEduLevel"][text])
                elif text:
                    readWarnings.append(f"invalid reqEduLevels: {text}")
            createJob[readField] = list(dict.fromkeys(mapped)) or None
        elif readField == "recommended":
            createJob[readField] = bool(readValue) if readValue is not None else False
        else:
            readText = normalizeText(readValue)
            createJob[readField] = readText[:TEXT_LIMITS.get(readField, len(readText))] or None

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

    for readField in ("companyName", "positionName"):
        if not createJob.get(readField):
            readWarnings.append(f"missing {readField}")
    createJob = {
        readField: readValue for readField, readValue in createJob.items()
        if readValue is not None and not (readField == "recommended" and readValue is False)
    }
    return createJob, readWarnings


def deduplicateJobs(readJobs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return deduplicateJobRecords(
        readJobs,
        crossSource=getattr(settings, "job_record_dedup_enabled", True),
    )
