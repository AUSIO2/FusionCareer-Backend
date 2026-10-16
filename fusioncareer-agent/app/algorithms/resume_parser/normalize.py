"""Normalize resume model output to Java profile and resume patches."""

from __future__ import annotations

import json
import re
from typing import Any

PROFILE_FIELDS = {
    "realName", "gender", "birthDate", "politicalStatus", "phone", "email", "wechat",
    "hometown", "grade", "major", "eduLevel", "supervisor", "intentionOrder",
    "intentionCity", "intentionDream", "mindset",
}
RESUME_FIELDS = {
    "personalIntro", "basicInfo", "education", "internship", "campus", "awards",
    "skills", "portfolio", "remark",
}
FIELD_NAMES = {
    "real_name": "realName", "birth_date": "birthDate", "political_status": "politicalStatus",
    "edu_level": "eduLevel", "intention_order": "intentionOrder",
    "intention_city": "intentionCity", "intention_dream": "intentionDream",
    "personal_intro": "personalIntro", "basic_info": "basicInfo",
}
ENUM_CODES = {
    "gender": {1: "MALE", 2: "FEMALE", 3: "OTHER"},
    "politicalStatus": {1: "MASSES", 2: "LEAGUE_MEMBER", 3: "PARTY_MEMBER", 4: "OTHER"},
    "eduLevel": {1: "UNDERGRADUATE", 2: "ACADEMIC_MASTER", 3: "PROFESSIONAL_MASTER", 4: "DOCTORAL"},
    "mindset": {
        1: "CONFIDENT", 2: "CAUTIOUSLY_OPTIMISTIC", 3: "LACK_OF_CONFIDENCE",
        4: "VERY_ANXIOUS", 5: "ZEN_WAITING",
    },
}
PROFILE_TEXT_LIMITS = {
    "realName": 32, "phone": 20, "email": 64, "wechat": 64, "hometown": 64,
    "grade": 16, "major": 64, "supervisor": 64, "intentionOrder": 64,
    "intentionDream": 256,
}
RESUME_TEXT_LIMITS = {
    "personalIntro": 300,
    "basicInfo": 16_000,
    "education": 16_000,
    "internship": 16_000,
    "campus": 16_000,
    "awards": 16_000,
    "skills": 16_000,
    "portfolio": 16_000,
    "remark": 16_000,
}


def normalizeDate(readValue: Any) -> str:
    readText = str(readValue or "").strip()
    if re.fullmatch(r"\d{4}-\d{2}", readText):
        return readText + "-01"
    return readText if re.fullmatch(r"\d{4}-\d{2}-\d{2}", readText) else ""


def normalizeResume(readResponse: dict[str, Any]) -> dict[str, Any]:
    readProfile = dict(readResponse.get("profilePatch") or {})
    readResume = dict(readResponse.get("resumePatch") or {})
    if not readProfile and not readResume:
        for readField, readValue in readResponse.items():
            updateField = FIELD_NAMES.get(readField, readField)
            if updateField in PROFILE_FIELDS:
                readProfile[updateField] = readValue
            elif updateField in RESUME_FIELDS:
                readResume[updateField] = readValue

    readWarnings = list(readResponse.get("warnings") or [])
    createProfile: dict[str, Any] = {}
    for readField, readValue in readProfile.items():
        updateField = FIELD_NAMES.get(readField, readField)
        if updateField not in PROFILE_FIELDS or readValue in (None, "", []):
            continue
        if updateField in ENUM_CODES:
            try:
                readCode = int(readValue)
            except (TypeError, ValueError):
                createProfile[updateField] = str(readValue).strip()
            else:
                if readCode in ENUM_CODES[updateField]:
                    createProfile[updateField] = ENUM_CODES[updateField][readCode]
        elif updateField == "birthDate":
            readDate = normalizeDate(readValue)
            if readDate:
                createProfile[updateField] = readDate
        elif updateField == "intentionCity":
            readCities = readValue if isinstance(readValue, list) else [readValue]
            readCities = [str(readCity).strip() for readCity in readCities if str(readCity).strip()]
            if len(readCities) > 20 or any(len(readCity) > 64 for readCity in readCities):
                readWarnings.append("truncated:intentionCity")
            readCities = [readCity[:64] for readCity in readCities[:20]]
            if readCities:
                createProfile[updateField] = json.dumps(readCities, ensure_ascii=False)
        else:
            readText = str(readValue).strip()
            readLimit = PROFILE_TEXT_LIMITS.get(updateField)
            if readLimit is not None and len(readText) > readLimit:
                # Truncating identifiers such as email or phone can create false data.
                readWarnings.append(f"over_limit:{updateField}")
                continue
            createProfile[updateField] = readText

    createResume: dict[str, str] = {}
    for readField, readValue in readResume.items():
        updateField = FIELD_NAMES.get(readField, readField)
        if updateField not in RESUME_FIELDS or readValue in (None, "", []):
            continue
        readText = str(readValue).strip()
        readLimit = RESUME_TEXT_LIMITS[updateField]
        if len(readText) > readLimit:
            readText = readText[:readLimit]
            readWarnings.append(f"truncated:{updateField}")
        createResume[updateField] = readText
    return {
        "profilePatch": createProfile,
        "resumePatch": createResume,
        "warnings": list(dict.fromkeys(readWarnings)),
    }
