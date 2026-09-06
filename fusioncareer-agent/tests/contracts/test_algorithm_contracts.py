import json
import re
from pathlib import Path


READ_FIXTURES = Path(__file__).parents[1] / "fixtures" / "algorithm"
READ_JOB_ENUMS = {
    "jobCategory": {"ACADEMIC", "GOVERNMENT", "MEDIA", "ENTERPRISE", "OTHER"},
    "recruitType": {
        "BIG_INTERNSHIP", "SMALL_INTERNSHIP", "DAILY_INTERNSHIP",
        "CAMPUS_RECRUITMENT", "CAMPUS_SCREENING", "OTHER",
    },
    "status": {"OFFLINE"},
}
READ_PROFILE_ENUMS = {
    "gender": {"MALE", "FEMALE", "OTHER"},
    "politicalStatus": {"MASSES", "LEAGUE_MEMBER", "PARTY_MEMBER", "OTHER"},
    "eduLevel": {"UNDERGRADUATE", "ACADEMIC_MASTER", "PROFESSIONAL_MASTER", "DOCTORAL"},
}


def readFixture(readName: str) -> dict:
    return json.loads((READ_FIXTURES / readName).read_text(encoding="utf-8"))


def testJobContract():
    readContract = readFixture("job_contract.json")

    assert len(readContract["jobs"]) == 2
    for readJob in readContract["jobs"]:
        assert readJob["companyName"]
        assert readJob["positionName"]
        for readField, readValues in READ_JOB_ENUMS.items():
            assert readJob[readField] in readValues


def testResumeContract():
    readContract = readFixture("resume_contract.json")
    readProfile = readContract["profilePatch"]

    assert all(readValue not in (None, "", []) for readValue in readProfile.values())
    assert all(readValue not in (None, "", []) for readValue in readContract["resumePatch"].values())
    for readField, readValues in READ_PROFILE_ENUMS.items():
        assert readProfile[readField] in readValues
    if "birthDate" in readProfile:
        assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", readProfile["birthDate"])
    assert isinstance(json.loads(readProfile["intentionCity"]), list)
