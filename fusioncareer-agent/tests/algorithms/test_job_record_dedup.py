from copy import deepcopy
from itertools import permutations

import pytest

from app.algorithms.job_record_dedup import (
    JobDuplicateIndex,
    applicationEmails,
    canonicalUrl,
    companyAliases,
    deduplicateJobRecords,
)
from app.algorithms.job_structuring import normalize as normalize_module
from app.algorithms.job_structuring.normalize import deduplicateJobs

APPLY = "https://huicecom.zhiye.com/Campus"
EMAIL = "hr@huice.com"


def huiceJob(source: str, company: str, *, position: str = "行政管培生", year: str = "2027届",
             city: str = "北京", apply: str = APPLY, email: str = EMAIL, status: str = "OFFLINE") -> dict:
    return {
        "sourceUrl": source,
        "companyName": company,
        "positionName": position,
        "workCity": city,
        "reqGradYear": year,
        "applicationDeadline": "2026-10-30",
        "recruitType": "CAMPUS_RECRUITMENT",
        "reqOther": f"网申链接：{apply}；投递邮箱：{email}",
        "status": status,
    }


def testCanonicalUrlDropsTrackingButKeepsBusinessParameters():
    assert canonicalUrl("http://WWW.Example.com/Campus/?utm_source=a&jobId=123#apply") \
        == "example.com/Campus?jobId=123"
    assert canonicalUrl("https://example.com/Campus?jobId=456") \
        != canonicalUrl("https://example.com/Campus?jobId=123")
    assert canonicalUrl("https://example.com/campus#/jobs/123") \
        != canonicalUrl("https://example.com/campus#/jobs/456")
    assert canonicalUrl("https://example.com/Campus#/") == canonicalUrl("https://example.com/Campus")
    assert canonicalUrl("https://example.com/jobs/123?locale=zh-CN") \
        == canonicalUrl("https://example.com/jobs/123")


def testCompanyAliasesRecognizeBrandAndLegalEntityInEitherOrder():
    first = companyAliases("慧策集团（北京掌上先机网络科技有限公司）")
    second = companyAliases("北京掌上先机网络科技有限公司（慧策集团）")
    assert "慧策集团" in first
    assert "北京掌上先机网络科技有限公司" in first
    assert first == second
    assert companyAliases("慧策集团") & companyAliases("北京掌上先机网络科技有限公司")


def testWeakCompanySuffixAndDescriptiveBracketsDoNotBecomeStrongAliases():
    assert not companyAliases("上海星辰科技有限公司") & companyAliases("上海星辰网络科技有限公司")
    assert not companyAliases("甲公司（国有企业）") & companyAliases("乙公司（国有企业）")
    assert not companyAliases("甲集团（上市公司）") & companyAliases("乙集团（上市公司）")


def testHuiceRepostsDeduplicateAcrossAliasesSourcesCitiesAndDefaultDeadlines():
    jobs = [
        huiceJob("https://career.fudan.edu.cn/a", "慧策集团（北京掌上先机网络科技有限公司）"),
        huiceJob("https://jy.xmu.edu.cn/b", "北京掌上先机网络科技有限公司", city="北京、天津"),
        huiceJob("https://xsjy.whu.edu.cn/c", "慧策集团"),
        huiceJob("https://career.bnu.edu.cn/d", "北京掌上先机网络科技有限公司（慧策集团）"),
    ]
    jobs[1]["applicationDeadline"] = "2026-10-29"
    jobs[1]["recruitType"] = 4

    result = deduplicateJobs(jobs)
    assert len(result) == 1
    assert set(result[0]["workCities"]) == {"北京", "天津"}
    assert result[0]["applicationDeadline"] == "2026-10-30"


def testGenericAtsCampaignUrlDeduplicatesWithoutSharedEmail():
    first = huiceJob(
        "https://career.fudan.edu.cn/repost/a",
        "慧策集团",
        email="",
    )
    second = huiceJob(
        "https://jy.xmu.edu.cn/repost/b",
        "北京掌上先机网络科技有限公司",
        email="",
    )

    assert deduplicateJobRecords([first, second]) == [first]


def testTenantAtsHostnameIsCampaignEvidenceEvenAtRoot():
    first = huiceJob("https://example.test/a", "慧策集团", apply="https://huicecom.zhiye.com/", email="")
    second = huiceJob("https://example.test/b", "慧策集团", apply="https://huicecom.zhiye.com/", email="")

    assert deduplicateJobRecords([first, second]) == [first]


def testProviderAtsHomepageWithoutTenantIsNotCampaignEvidence():
    first = huiceJob("https://example.test/a", "慧策集团", apply="https://www.zhiye.com/Campus", email="")
    second = huiceJob("https://example.test/b", "慧策集团", apply="https://www.zhiye.com/Campus", email="")

    assert deduplicateJobRecords([first, second]) == [first, second]


def testPublicJobBoardPathWithoutEmailIsNotTenantCampaignEvidence():
    first = huiceJob("https://example.test/a", "慧策集团", apply="https://www.51job.com/campus", email="")
    second = huiceJob("https://example.test/b", "慧策集团", apply="https://www.51job.com/campus", email="")

    assert deduplicateJobRecords([first, second]) == [first, second]


def testGenericFeishuSearchPathIsNotTenantCampaignEvidence():
    first = huiceJob("https://example.test/a", "慧策集团", apply="https://jobs.feishu.cn/search/campus", email="")
    second = huiceJob("https://example.test/b", "慧策集团", apply="https://jobs.feishu.cn/search/campus", email="")

    assert deduplicateJobRecords([first, second]) == [first, second]


def testMokaTenantCampaignPathsAreStrongAnchors():
    for apply in (
        "https://app.mokahr.com/su/1a1Go4",
        "https://app.mokahr.com/campus_apply/geekplus",
        "https://app.mokahr.com/m/campus-recruitment/geekplus/2027",
        "https://app.mokahr.com/m/campus_apply/geekplus",
    ):
        first = huiceJob("https://example.test/a", "慧策集团", apply=apply, email="")
        second = huiceJob("https://example.test/b", "慧策集团", apply=apply, email="")
        assert deduplicateJobRecords([first, second]) == [first]


def testGenericMokaSearchPathIsNotTenantCampaignEvidence():
    first = huiceJob(
        "https://example.test/a", "慧策集团",
        apply="https://app.mokahr.com/m/search/campus", email="",
    )
    second = huiceJob(
        "https://example.test/b", "慧策集团",
        apply="https://app.mokahr.com/m/search/campus", email="",
    )

    assert deduplicateJobRecords([first, second]) == [first, second]


def testChineseTextMayTouchEmailWithoutHidingIt():
    job = huiceJob("https://example.test/a", "慧策集团", email="")
    job["reqOther"] = "投递邮箱hr@huice.com"

    assert applicationEmails(job) == {"hr@huice.com"}


def testNormalizeDedupKillSwitchRestoresExactSourceKeys(monkeypatch):
    monkeypatch.setattr(normalize_module.settings, "job_record_dedup_enabled", False)
    jobs = [
        huiceJob("https://example.test/a", "慧策集团"),
        huiceJob("https://example.test/b", "北京掌上先机网络科技有限公司"),
    ]
    assert deduplicateJobs(jobs) == jobs


def testSameCampaignKeepsDifferentPositions():
    jobs = [
        huiceJob("https://example.test/a", "慧策集团", position="行政管培生"),
        huiceJob("https://example.test/b", "慧策集团", position="产品管培生"),
    ]
    assert deduplicateJobRecords(jobs) == jobs


def testPositionNormalizationPreservesProgrammingLanguageSymbols():
    jobs = [
        huiceJob("https://example.test/a", "慧策集团", position="C++开发工程师", email=""),
        huiceJob("https://example.test/b", "慧策集团", position="C#开发工程师", email=""),
    ]
    assert deduplicateJobRecords(jobs) == jobs


def testExplicitDifferentCampaignYearsRemainDistinct():
    jobs = [
        huiceJob("https://example.test/a", "慧策集团", year="2027届", email=""),
        huiceJob("https://example.test/b", "慧策集团", year="2028届", email=""),
    ]
    assert deduplicateJobRecords(jobs) == jobs


def testMissingYearUsesAcademicCycleAsCampaignBoundary():
    first = huiceJob("https://example.test/a", "慧策集团", year="")
    first["createdAt"] = "2026-09-30 20:00:00"
    second = huiceJob("https://example.test/b", "慧策集团", year="2028届")
    assert deduplicateJobRecords([first, second]) == [first, second]


def testUnrelatedCompanyHistoryYearIsNotCampaignBoundary():
    first = huiceJob("https://example.test/a", "慧策集团", year="")
    first["jobDesc"] = "公司成立于2012年，现招聘行政管培生。"
    second = huiceJob("https://example.test/b", "慧策集团", year="")
    assert deduplicateJobRecords([first, second]) == [first]


def testDisjointCitiesRemainDistinct():
    jobs = [
        huiceJob("https://example.test/a", "慧策集团", city="北京"),
        huiceJob("https://example.test/b", "慧策集团", city="上海"),
    ]
    assert deduplicateJobRecords(jobs) == jobs


def testUnknownCityCannotBridgeConflictingCitiesInAnyInputOrder():
    jobs = [
        huiceJob("https://example.test/unknown", "慧策集团", city="", email=""),
        huiceJob("https://example.test/beijing", "慧策集团", city="北京", email=""),
        huiceJob("https://example.test/shanghai", "慧策集团", city="上海", email=""),
    ]
    for order in permutations(jobs):
        result = deduplicateJobRecords(deepcopy(order))
        assert len(result) == 2
        assert all(set(job.get("workCities") or []) != {"北京", "上海"} for job in result)


def testNationalCoverageCityWordingIsCanonicalized():
    first = huiceJob("https://example.test/a", "慧策集团", city="全国", email="")
    second = huiceJob("https://example.test/b", "慧策集团", city="全国100多个主要城市（可就近选择）", email="")
    third = huiceJob("https://example.test/c", "慧策集团", city="全国100+城市", email="")

    fourth = huiceJob("https://example.test/d", "慧策集团", city="全国（就近安排）", email="")
    fifth = huiceJob("https://example.test/e", "慧策集团", city="全国（含港澳台）", email="")

    assert deduplicateJobRecords([first, second, third, fourth, fifth]) == [first]


def testNationalHeadquartersTextIsNotNationwideCoverageWildcard():
    first = huiceJob("https://example.test/a", "慧策集团", city="全国", email="")
    second = huiceJob("https://example.test/b", "慧策集团", city="全国总部（北京）", email="")

    assert deduplicateJobRecords([first, second]) == [first, second]


def testOneCompatibleCityClusterCanMatchBesideAConflictingCluster():
    beijing = huiceJob("https://example.test/beijing", "慧策集团", city="北京", email="")
    shanghai = huiceJob("https://example.test/shanghai", "慧策集团", city="上海", email="")
    incoming = huiceJob("https://example.test/beijing-copy", "慧策集团", city="北京", email="")
    unknown = huiceJob("https://example.test/unknown", "慧策集团", city="", email="")
    index = JobDuplicateIndex([beijing, shanghai])

    match = index.find(incoming)
    assert match is not None
    assert match.winner is beijing
    unknownMatch = index.find(unknown)
    assert unknownMatch is not None
    index.recordDuplicate(unknownMatch, unknown)
    shanghaiCopy = huiceJob(
        "https://example.test/shanghai-copy", "慧策集团", city="上海", email="",
    )
    shanghaiMatch = index.find(shanghaiCopy)
    assert shanghaiMatch is not None
    assert shanghaiMatch.winner is shanghai


def testConflictingExistingEvidencePreventsUnknownRowFromBecomingKeeper():
    unknown = huiceJob("https://example.test/unknown", "慧策集团", city="", email="")
    beijing = huiceJob("https://example.test/beijing", "慧策集团", city="北京", email="")
    shanghai = huiceJob("https://example.test/shanghai", "慧策集团", city="上海", email="")
    assert JobDuplicateIndex([unknown, beijing]).find(shanghai) is None


def testPersistedEmptyKeeperCannotDiscardHardEvidenceAcrossRuns():
    persistedUnknown = {
        **huiceJob("https://example.test/keeper", "慧策集团", city="", email=""),
        "id": "1001",
    }
    beijing = huiceJob("https://example.test/beijing", "慧策集团", city="北京", email="")
    assert JobDuplicateIndex([persistedUnknown]).find(beijing) is None

    persistedBeijing = {**beijing, "id": "1002"}
    shanghai = huiceJob("https://example.test/shanghai", "慧策集团", city="上海", email="")
    rebuilt = JobDuplicateIndex([persistedUnknown, persistedBeijing])
    assert rebuilt.find(shanghai) is None


def testMissingEvidenceCannotBridgeDepartmentOrSalaryConflictsInAnyInputOrder():
    cases = [
        ("department", "行政部", "销售部"),
        ("salaryMin", 8000, 12000),
        ("salaryDisplay", "面议", "150元/天"),
    ]
    for field, firstValue, secondValue in cases:
        jobs = [
            {**huiceJob("https://example.test/unknown", "慧策集团", email=""), field: ""},
            {**huiceJob("https://example.test/first", "慧策集团", email=""), field: firstValue},
            {**huiceJob("https://example.test/second", "慧策集团", email=""), field: secondValue},
        ]
        for order in permutations(jobs):
            result = deduplicateJobRecords(deepcopy(order))
            assert len(result) == 2
            assert {job.get(field) for job in result if job.get(field)} == {firstValue, secondValue}


def testExplicitSalaryConflictRemainsDistinct():
    first = huiceJob("https://example.test/a", "慧策集团")
    second = huiceJob("https://example.test/b", "慧策集团")
    first["salaryMin"] = 8000
    second["salaryMin"] = 12000
    assert deduplicateJobRecords([first, second]) == [first, second]


def testDisplayOnlyHeadcountAndSalaryConflictsRemainDistinct():
    pairs = [
        ({"headcountDisplay": "若干"}, {"headcountDisplay": "10人"}),
        ({"salaryDisplay": "面议"}, {"salaryDisplay": "150元/天"}),
    ]
    for firstPatch, secondPatch in pairs:
        first = {**huiceJob("https://example.test/a", "慧策集团", email=""), **firstPatch}
        second = {**huiceJob("https://example.test/b", "慧策集团", email=""), **secondPatch}
        assert deduplicateJobRecords([first, second]) == [first, second]


def testSalaryAndBenefitsDisplayTreatsPunctuationOnlyDifferencesAsEquivalent():
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["salaryDisplay"] = "基本工资+绩效工资；福利：五险一金、餐补"
    second["salaryDisplay"] = "基本工资＋绩效工资,福利:五险一金，餐补。"

    assert deduplicateJobRecords([first, second]) == [first]


def testNumericSalaryRangeSeparatorsAreEquivalent():
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["salaryDisplay"] = "15-20万/年"
    second["salaryDisplay"] = "15～20万／年"

    assert deduplicateJobRecords([first, second]) == [first]


def testExplicitMajorEducationAndLocationConflictsRemainDistinct():
    pairs = [
        ({"reqMajor": "计算机类"}, {"reqMajor": "新闻传播类"}),
        ({"reqEduLevel": "UNDERGRADUATE"}, {"reqEduLevel": "DOCTORAL"}),
        ({"reqEduLevels": ["UNDERGRADUATE"]}, {"reqEduLevels": ["DOCTORAL"]}),
        ({"workLocation": "北京A座"}, {"workLocation": "北京B座"}),
    ]
    for firstPatch, secondPatch in pairs:
        first = {**huiceJob("https://example.test/a", "慧策集团", email=""), **firstPatch}
        second = {**huiceJob("https://example.test/b", "慧策集团", email=""), **secondPatch}
        assert deduplicateJobRecords([first, second]) == [first, second]


@pytest.mark.parametrize(
    ("first_major", "second_major"),
    [
        ("C++", "C#"),
        ("不限专业", "计算机类"),
    ],
)
def testSemanticMajorSymbolsAndUnrestrictedScopeRemainDistinct(
    first_major, second_major,
):
    first = {
        **huiceJob("https://example.test/a", "慧策集团", email=""),
        "reqMajor": first_major,
    }
    second = {
        **huiceJob("https://example.test/b", "慧策集团", email=""),
        "reqMajor": second_major,
    }

    assert deduplicateJobRecords([first, second]) == [first, second]


@pytest.mark.parametrize(
    ("first_scope", "second_scope"),
    [
        ("非应届生", "应届生"),
        ("非应届生、实习生", "应届生、实习生"),
        ("往届生", "应届毕业生"),
    ],
)
def testConflictingGraduationEligibilityRemainsDistinct(first_scope, second_scope):
    first = {
        **huiceJob("https://example.test/a", "慧策集团", year="", email=""),
        "reqGradYear": first_scope,
    }
    second = {
        **huiceJob("https://example.test/b", "慧策集团", year="", email=""),
        "reqGradYear": second_scope,
    }

    assert deduplicateJobRecords([first, second]) == [first, second]


@pytest.mark.parametrize(
    ("first_id", "second_id"),
    [("HC001", "HC002"), ("ABCDEF", "MKTG")],
)
def testTextualExternalJobIdsRemainDistinct(first_id, second_id):
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["reqOther"] += f"；职位ID：{first_id}"
    second["reqOther"] += f"；职位ID：{second_id}"

    assert deduplicateJobRecords([first, second]) == [first, second]


@pytest.mark.parametrize(
    ("field", "first_value", "second_value"),
    [
        ("workStartDate", "2026-07-01", "2026-08-01"),
        ("workEndDate", "2026-12-31", "2027-01-31"),
        ("workDaysPerWeek", 3, 5),
        ("workDurationType", "ONE_TO_THREE_MONTHS", "THREE_TO_SIX_MONTHS"),
        ("workPeriodType", "FULL_TIME", "PART_TIME"),
        ("workProvince", "北京", "上海"),
    ],
)
def testExplicitWorkArrangementConflictsRemainDistinct(field, first_value, second_value):
    first = {**huiceJob("https://example.test/a", "慧策集团", email=""), field: first_value}
    second = {**huiceJob("https://example.test/b", "慧策集团", email=""), field: second_value}

    assert deduplicateJobRecords([first, second]) == [first, second]


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("workStartDate", "2026-07-01"),
        ("workEndDate", "2026-12-31"),
        ("workDaysPerWeek", 5),
        ("workDurationType", "THREE_TO_SIX_MONTHS"),
        ("workPeriodType", "FULL_TIME"),
        ("workProvince", "北京"),
    ],
)
def testPersistedKeeperRequiresMergeForNewWorkArrangementEvidence(field, value):
    keeper = {**huiceJob("https://example.test/keeper", "慧策集团", email=""), "id": "1001"}
    incoming = {**huiceJob("https://example.test/incoming", "慧策集团", email=""), field: value}
    index = JobDuplicateIndex([keeper])

    assert index.find(incoming) is None
    match = index.find(incoming, allowPersistedUpdate=True)
    assert match is not None and match.requiresPersistedUpdate


def testCityCountMarketingCopyIsNotTreatedAsAStreetAddress():
    first = huiceJob("https://example.test/a", "慧策集团", city="", email="")
    second = huiceJob("https://example.test/b", "慧策集团", city="", email="")
    first["workLocation"] = "业务覆盖全国100+城市"
    second["workLocation"] = "覆盖全国100余个城市"

    assert deduplicateJobRecords([first, second]) == [first]


def testChannelWordingIsNotTreatedAsAStreetAddress():
    first = huiceJob("https://example.test/a", "慧策集团", city="", email="")
    second = huiceJob("https://example.test/b", "慧策集团", city="", email="")
    first["workLocation"] = "覆盖线上渠道"
    second["workLocation"] = "线上渠道覆盖"

    assert deduplicateJobRecords([first, second]) == [first]


def testDifferentConcreteStreetAddressesRemainDistinctWithoutEmail():
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["workLocation"] = "北京市海淀区知春路66号"
    second["workLocation"] = "北京市朝阳区望京东路8号"

    assert deduplicateJobRecords([first, second]) == [first, second]


def testStreetAddressesWithoutAdministrativeSuffixesRemainDistinct():
    pairs = [
        ("北京海淀知春路", "上海浦东世纪大道"),
        ("北京望京SOHO T1", "上海张江高科2期"),
    ]
    for firstLocation, secondLocation in pairs:
        first = huiceJob("https://example.test/a", "慧策集团", city="", email="")
        second = huiceJob("https://example.test/b", "慧策集团", city="", email="")
        first["workLocation"] = firstLocation
        second["workLocation"] = secondLocation
        assert deduplicateJobRecords([first, second]) == [first, second]


def testExplicitRecruitingSeasonsRemainDistinct():
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["jobDesc"] = "2027届秋招行政管培生"
    second["jobDesc"] = "2027届春招行政管培生"
    assert deduplicateJobRecords([first, second]) == [first, second]


def testSharedHrEmailWithoutApplicationUrlIsNotEnough():
    jobs = [
        huiceJob("https://example.test/a", "慧策集团", apply=""),
        huiceJob("https://example.test/b", "慧策集团", apply=""),
    ]
    assert deduplicateJobRecords(jobs) == jobs


def testGenericAtsCampaignUrlWithoutEmailDoesNotBridgeUnrelatedCompanies():
    first = huiceJob("https://example.test/a", "甲公司", email="")
    second = huiceJob("https://example.test/b", "乙公司", email="")
    assert deduplicateJobRecords([first, second]) == [first, second]


def testGenericCampaignUrlAndMailboxDoNotBridgeUnrelatedCompanies():
    first = huiceJob("https://example.test/a", "甲公司", apply="https://jobs.example.com/campus")
    second = huiceJob("https://example.test/b", "乙公司", apply="https://jobs.example.com/campus")
    assert deduplicateJobRecords([first, second]) == [first, second]


def testSharedMailboxSelectsCompatibleCandidateBeyondFirstCluster():
    apply = "https://careers.example.com/campus"
    xzhr = huiceJob("https://example.test/xz", "慧策集团", apply=apply, email="xzhr@huice.com")
    hr = huiceJob("https://example.test/hr", "慧策集团", apply=apply, email="hr@huice.com")
    incoming = huiceJob(
        "https://example.test/hr-copy", "慧策集团", apply=apply, email="hr@huice.com",
    )
    index = JobDuplicateIndex([xzhr, hr])

    match = index.find(incoming)
    assert match is not None
    assert match.winner is hr


def testSharedNonApplicationUrlDoesNotTriggerDeduplication():
    first = huiceJob("https://example.test/a", "慧策集团", apply="")
    second = huiceJob("https://example.test/b", "慧策集团", apply="")
    first["reqOther"] += "；直播链接：https://live.example/session/2027"
    second["reqOther"] += "；直播链接：https://live.example/session/2027"
    assert deduplicateJobRecords([first, second]) == [first, second]


def testDifferentJobUrlsOverrideSharedCampaignHomepage():
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["reqOther"] += "；职位链接：https://jobs.example.com/jobs/111111"
    second["reqOther"] += "；职位链接：https://jobs.example.com/jobs/222222"
    assert deduplicateJobRecords([first, second]) == [first, second]


def testOverlappingJobUrlSetsRemainDistinct():
    first = huiceJob("https://example.test/a", "慧策集团", email="")
    second = huiceJob("https://example.test/b", "慧策集团", email="")
    first["reqOther"] += "；职位链接：https://jobs.example.com/jobs/111111"
    second["reqOther"] += (
        "；职位链接：https://jobs.example.com/jobs/111111"
        "；岗位链接：https://jobs.example.com/jobs/222222"
    )

    assert deduplicateJobRecords([first, second]) == [first, second]


def testOverlappingYearMajorAndEducationSetsRemainDistinct():
    pairs = [
        ({"reqGradYear": "2027届"}, {"reqGradYear": "2027届、2028届"}),
        ({"reqMajor": "计算机类"}, {"reqMajor": "计算机类、数学类"}),
        (
            {"reqEduLevels": ["UNDERGRADUATE"]},
            {"reqEduLevels": ["UNDERGRADUATE", "MASTER"]},
        ),
    ]
    for firstPatch, secondPatch in pairs:
        first = {**huiceJob("https://example.test/a", "慧策集团", email=""), **firstPatch}
        second = {**huiceJob("https://example.test/b", "慧策集团", email=""), **secondPatch}
        assert deduplicateJobRecords([first, second]) == [first, second]


def testEmptySourceUrlIsNotAnExactSourceIdentity():
    first = {"sourceUrl": "", "companyName": "示例公司", "positionName": "编辑"}
    second = {**first, "jobDesc": "另一条没有投递证据的记录"}

    assert deduplicateJobRecords([first, second]) == [first, second]


def testPersistedKeeperCannotDiscardAdditionalCityEvidence():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", city="北京", email=""),
        "id": "1001",
    }
    incoming = huiceJob("https://example.test/incoming", "慧策集团", city="北京、上海", email="")

    assert JobDuplicateIndex([keeper]).find(incoming) is None


def testExplicitPersistedMarkerProtectsCommittedRowWithoutReturnedId():
    keeper = huiceJob("https://example.test/keeper", "慧策集团", city="北京", email="")
    incoming = huiceJob("https://example.test/incoming", "慧策集团", city="北京、上海", email="")
    index = JobDuplicateIndex()
    index.add(keeper, persisted=True)

    assert index.find(incoming) is None


def testPersistedKeeperCannotDiscardLaterDeadlineOrRecommendation():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", email=""),
        "id": "1001",
        "applicationDeadline": "2099-10-16",
        "recommended": False,
    }
    later = {
        **huiceJob("https://example.test/later", "慧策集团", email=""),
        "applicationDeadline": "2099-10-30",
    }
    recommended = {
        **huiceJob("https://example.test/recommended", "慧策集团", email=""),
        "applicationDeadline": "2099-10-16",
        "recommended": True,
    }
    earlier = {
        **huiceJob("https://example.test/earlier", "慧策集团", email=""),
        "applicationDeadline": "2099-10-15",
        "recommended": "0",
    }
    index = JobDuplicateIndex([keeper])

    assert index.find(later) is None
    mergeMatch = index.find(later, allowPersistedUpdate=True)
    assert mergeMatch is not None and mergeMatch.requiresPersistedUpdate
    assert index.find(recommended) is None
    assert index.find(earlier) is not None


def testLegacySyntheticOneMonthDeadlineIsTreatedAsMissingEvidence():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", email=""),
        "id": "1001",
        "createdAt": "2026-10-01T08:00:00",
        "applicationDeadline": "2026-11-01",
    }
    incoming = {
        **huiceJob("https://example.test/incoming", "慧策集团", email=""),
        "applicationDeadline": "2026-10-15",
    }
    index = JobDuplicateIndex([keeper])

    assert index.find(incoming) is None
    match = index.find(incoming, allowPersistedUpdate=True)
    assert match is not None and match.requiresPersistedUpdate


def testPersistedExpiredKeeperCannotHideCurrentlyVisibleIncoming():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", email=""),
        "id": "1001",
        "applicationDeadline": "2000-01-01",
    }
    incoming = {
        **huiceJob("https://example.test/incoming", "慧策集团", email=""),
        "applicationDeadline": "2099-01-01",
    }

    assert JobDuplicateIndex([keeper]).find(incoming) is None


def testExplicitExpiredKeeperCannotAbsorbActiveRepostEvenWithMergeEnabled():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", email=""),
        "id": "1001",
        "status": "EXPIRED",
        "applicationDeadline": "2026-01-01",
    }
    incoming = {
        **huiceJob("https://example.test/incoming", "慧策集团", email=""),
        "status": "OFFLINE",
        "applicationDeadline": "2026-10-30",
    }

    assert JobDuplicateIndex([keeper]).find(
        incoming, allowPersistedUpdate=True,
    ) is None


def testStringZeroDoesNotEnableRecommendationDuringMerge():
    first = {**huiceJob("https://example.test/a", "慧策集团"), "recommended": False}
    second = {**huiceJob("https://example.test/b", "慧策集团"), "recommended": "0"}

    result = deduplicateJobRecords([first, second])
    assert len(result) == 1
    assert result[0]["recommended"] is False


def testSecondaryCampaignAnchorIndexesBackToClusterRoot():
    first = huiceJob(
        "https://example.test/a", "慧策集团",
        apply="https://careers.example.com/campaign/primary",
    )
    second = huiceJob(
        "https://example.test/b", "慧策集团",
        apply="https://careers.example.com/campaign/primary",
    )
    second["reqOther"] += "；网申：https://careers.example.com/campaign/secondary"
    incoming = huiceJob(
        "https://example.test/c", "慧策集团",
        apply="https://careers.example.com/campaign/secondary",
    )
    index = JobDuplicateIndex([first, second])

    match = index.find(incoming)
    assert match is not None
    assert match.winner is second


def testCompleteLinkPartitionIsStableAcrossMixedMissingEvidence():
    jobs = [
        {**huiceJob("https://example.test/a", "慧策集团", city="北京", email=""), "department": "甲部"},
        {**huiceJob("https://example.test/b", "慧策集团", city="北京", email=""), "department": ""},
        {**huiceJob("https://example.test/c", "慧策集团", city="", email=""), "department": "乙部"},
        {**huiceJob("https://example.test/d", "慧策集团", city="上海", email=""), "department": "乙部"},
    ]
    for order in permutations(jobs):
        result = deduplicateJobRecords(deepcopy(order))
        assert len(result) == 2
        assert {
            (tuple(sorted(job.get("workCities") or [])), job.get("department"))
            for job in result
        } == {(('上海',), '乙部'), (('北京',), '甲部')}


def testPersistedEvidenceRowsRestoreCityCompleteLinkAfterRestart():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", city="北京、上海、深圳", email=""),
        "id": "1001",
        "workCities": ["北京", "上海", "深圳"],
    }
    incomingEvidence = {
        **huiceJob("https://example.test/incoming", "慧策集团", city="上海、深圳", email=""),
        "id": "1002",
        "workCities": ["上海", "深圳"],
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=incoming;sha256=" + "a" * 64
        ),
    }
    bridge = {
        **huiceJob("https://example.test/bridge", "慧策集团", city="北京、广州", email=""),
        "workCities": ["北京", "广州"],
    }

    assert JobDuplicateIndex([keeper, incomingEvidence]).find(bridge) is None


@pytest.mark.parametrize(
    "reason",
    [
        "dedup-evidence:v1;keeper=1001;role=incoming;sha256=" + "a" * 64,
        "semantic-merge:huice-training-semantic-merge-v1;keeper=1001;run=test",
        "semantic-dedup:huice-production-dedup-v2-delta;keeper=1001;run=test",
    ],
)
def testTrustedRecycledEvidenceReasonIsReattachedToKeeper(reason):
    evidence = {
        "id": "1002", "status": "RECYCLED", "recycleReason": reason,
    }

    assert JobDuplicateIndex._evidenceKeeperId(evidence) == "1001"


def testPersistedEvidenceRowsRestoreDeadlineFloorAfterRestart():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", email=""),
        "id": "1001",
        "applicationDeadline": "2026-06-01",
    }
    keeperBefore = {
        **huiceJob("https://example.test/original", "慧策集团", email=""),
        "id": "1002",
        "applicationDeadline": "2026-01-01",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=keeper-before;sha256=" + "b" * 64
        ),
    }
    laterBridge = {
        **huiceJob("https://example.test/later", "慧策集团", email=""),
        "applicationDeadline": "2026-11-01",
    }

    assert JobDuplicateIndex([keeper]).find(
        laterBridge, allowPersistedUpdate=True,
    ) is not None
    assert JobDuplicateIndex([keeper, keeperBefore]).find(
        laterBridge, allowPersistedUpdate=True,
    ) is None


def testSyntheticKeeperUnionIsNotUsedAsAnOriginalAnchor():
    primary = "https://huicecom.zhiye.com/Campus"
    secondary = "https://huicecom.zhiye.com/Campus2"
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", year="2027届", email=""),
        "id": "1001",
        "recruitType": None,
        "reqOther": f"网申：{primary}；网申：{secondary}；邮箱：xzhr@huice.com",
    }
    keeperBefore = {
        **huiceJob("https://example.test/before", "慧策集团", year="2027届", email=""),
        "id": "1002",
        "recruitType": None,
        "reqOther": f"网申：{primary}；邮箱：xzhr@huice.com",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=keeper-before;sha256=" + "b" * 64
        ),
    }
    incomingEvidence = {
        **huiceJob("https://example.test/evidence", "慧策集团", year="2027届", email=""),
        "id": "1003",
        "recruitType": None,
        "reqOther": f"网申：{secondary}",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=incoming;sha256=" + "c" * 64
        ),
    }
    spliced = {
        **huiceJob("https://example.test/spliced", "慧策集团", year="", email=""),
        "recruitType": None,
        "reqOther": f"网申：{secondary}；邮箱：xzhr@huice.com",
    }

    assert JobDuplicateIndex([keeper, keeperBefore, incomingEvidence]).find(spliced) is None


@pytest.mark.parametrize("identity_change", ["company", "season"])
def testEvidenceLineageDetachesAfterCanonicalIdentityChange(identity_change):
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", year="2027届", email=""),
        "id": "1001",
        "jobDesc": "2027届春招行政管培生" if identity_change == "season" else "2027届秋招行政管培生",
    }
    if identity_change == "company":
        keeper["companyName"] = "甲公司"
    keeperBefore = {
        **huiceJob("https://example.test/before", "慧策集团", year="2027届", email=""),
        "id": "1002",
        "jobDesc": "2027届秋招行政管培生",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=keeper-before;sha256=" + "2" * 64
        ),
    }
    incoming = {
        **huiceJob("https://example.test/incoming", "慧策集团", year="2027届", email=""),
        "jobDesc": "2027届秋招行政管培生",
    }

    assert JobDuplicateIndex([keeper, keeperBefore]).find(
        incoming, allowPersistedUpdate=True,
    ) is None


def testEvidenceLineageAllowsMonotonicLocationTextEnrichment():
    original = {
        **huiceJob("https://example.test/before", "慧策集团", email=""),
        "workLocation": "北京市海淀区知春路66号",
    }
    merged = {
        **original,
        "workLocation": "北京市海淀区知春路66号\n办公氛围好",
    }

    assert JobDuplicateIndex._sameCanonicalLineage(merged, original)


def testLegacyR2EvidenceDetachesAfterKeeperIdentityChange():
    keeper = {
        **huiceJob("https://example.test/keeper", "甲公司", year="2027届", email=""),
        "id": "1001",
    }
    legacyLoser = {
        **huiceJob("https://example.test/legacy", "慧策集团", year="2027届", email=""),
        "id": "1002",
        "status": "RECYCLED",
        "recycleReason": (
            "semantic-dedup:huice-production-dedup-v2-delta;keeper=1001;run=test"
        ),
    }
    incoming = huiceJob(
        "https://example.test/incoming", "慧策集团", year="2027届", email="",
    )

    assert JobDuplicateIndex([keeper, legacyLoser]).find(
        incoming, allowPersistedUpdate=True,
    ) is None


def testLegacyR2GenericLocationEvidenceStillRestoresCityBoundary():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", city="", email=""),
        "id": "1001",
        "workLocation": "",
    }
    legacyLoser = {
        **huiceJob("https://example.test/legacy", "慧策集团", city="北京", email=""),
        "id": "1002",
        "workLocation": "办公氛围好",
        "status": "RECYCLED",
        "recycleReason": (
            "semantic-dedup:huice-production-dedup-v2-delta;keeper=1001;run=test"
        ),
    }
    shanghai = huiceJob(
        "https://example.test/shanghai", "慧策集团", city="上海", email="",
    )

    assert JobDuplicateIndex([keeper, legacyLoser]).find(
        shanghai, allowPersistedUpdate=True,
    ) is None


def testEvidenceChainsAreResolvedToTheirUltimateKeeper():
    secondary = "https://huicecom.zhiye.com/Campus2"
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", year="2027届", email=""),
        "id": "2000",
        "recruitType": None,
    }
    oldKeeper = {
        **huiceJob("https://example.test/old", "慧策集团", year="2027届", email=""),
        "id": "1001",
        "recruitType": None,
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=2000;role=incoming;sha256=" + "d" * 64
        ),
    }
    chainedEvidence = {
        **huiceJob("https://example.test/chained", "慧策集团", year="2027届", email=""),
        "id": "1002",
        "recruitType": None,
        "salaryDisplay": "基本工资+绩效奖金+六险一金",
        "reqOther": f"网申：{secondary}；邮箱：xzhr@huice.com",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=incoming;sha256=" + "e" * 64
        ),
    }
    incoming = {
        **huiceJob("https://example.test/new", "慧策集团", year="", email=""),
        "recruitType": None,
        "salaryDisplay": "基本工资+绩效奖金+六险一金",
        "reqOther": f"网申：{secondary}；邮箱：xzhr@huice.com",
    }

    assert JobDuplicateIndex([keeper, chainedEvidence, oldKeeper]).find(incoming) is not None


def testSyntheticIntermediateKeeperIsExcludedAfterEvidenceChainResolution():
    primary = "https://huicecom.zhiye.com/Campus"
    secondary = "https://huicecom.zhiye.com/Campus2"
    finalKeeper = {
        **huiceJob("https://example.test/final", "慧策集团", year="2027届", email=""),
        "id": "2000",
        "recruitType": None,
        "reqOther": f"网申：{primary}",
    }
    syntheticOldKeeper = {
        **huiceJob("https://example.test/old", "慧策集团", year="2027届", email=""),
        "id": "1001",
        "recruitType": None,
        "reqOther": f"网申：{primary}；网申：{secondary}；邮箱：xzhr@huice.com",
        "status": "RECYCLED",
        "recycleReason": "semantic-merge:test;keeper=2000;run=test",
    }
    oldKeeperBefore = {
        **huiceJob("https://example.test/old-before", "慧策集团", year="2027届", email=""),
        "id": "1002",
        "recruitType": None,
        "reqOther": f"网申：{primary}；邮箱：xzhr@huice.com",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=keeper-before;sha256=" + "f" * 64
        ),
    }
    oldIncoming = {
        **huiceJob("https://example.test/old-incoming", "慧策集团", year="2027届", email=""),
        "id": "1003",
        "recruitType": None,
        "reqOther": f"网申：{secondary}",
        "status": "RECYCLED",
        "recycleReason": (
            "dedup-evidence:v1;keeper=1001;role=incoming;sha256=" + "1" * 64
        ),
    }
    spliced = {
        **huiceJob("https://example.test/spliced-chain", "慧策集团", year="", email=""),
        "recruitType": None,
        "reqOther": f"网申：{secondary}；邮箱：xzhr@huice.com",
    }

    index = JobDuplicateIndex([
        finalKeeper, oldIncoming, syntheticOldKeeper, oldKeeperBefore,
    ])
    assert index.find(spliced) is None


def testClusterMemberAnchorsAreAvailableBeforeRestart():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", year="2027届", email=""),
        "id": "1001",
        "recruitType": None,
    }
    firstDuplicate = {
        **huiceJob("https://example.test/first", "慧策集团", year="2027届", email="xzhr@huice.com"),
        "recruitType": None,
    }
    secondDuplicate = {
        **huiceJob("https://example.test/second", "慧策集团", year="", email="xzhr@huice.com"),
        "recruitType": None,
    }
    index = JobDuplicateIndex([keeper])
    firstMatch = index.find(firstDuplicate)

    assert firstMatch is not None
    index.recordDuplicate(firstMatch, firstDuplicate)
    assert index.find(secondDuplicate) is not None


def testClusterDoesNotSpliceUrlAndEmailFromDifferentMembers():
    primary = "https://huicecom.zhiye.com/Campus"
    secondary = "https://huicecom.zhiye.com/Campus2"
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团", year="2027届", email=""),
        "id": "1001",
        "recruitType": None,
        "reqOther": f"网申：{primary}",
    }
    urlMember = {
        **huiceJob("https://example.test/url", "慧策集团", year="2027届", email=""),
        "recruitType": None,
        "reqOther": f"网申：{primary}；网申：{secondary}",
    }
    emailMember = {
        **huiceJob("https://example.test/email", "慧策集团", year="2027届", email="xzhr@huice.com"),
        "recruitType": None,
        "reqOther": f"网申：{primary}；邮箱：xzhr@huice.com",
    }
    spliced = {
        **huiceJob("https://example.test/spliced", "慧策集团", year="", email="xzhr@huice.com"),
        "recruitType": None,
        "reqOther": f"网申：{secondary}；邮箱：xzhr@huice.com",
    }
    index = JobDuplicateIndex([keeper])
    for member in (urlMember, emailMember):
        match = index.find(member)
        assert match is not None
        index.recordDuplicate(match, member)

    assert index.find(spliced) is None


def testRecycledIncomingNeverPollutesActiveKeeper():
    keeper = {
        **huiceJob("https://example.test/keeper", "慧策集团"),
        "id": "1001",
        "status": "PUBLISHED",
    }
    filtered = {
        **huiceJob("https://example.test/filtered", "慧策集团"),
        "status": "RECYCLED",
    }

    assert JobDuplicateIndex([keeper]).find(filtered, allowPersistedUpdate=True) is None


def testOfficialAtsSourceMatchesUniversityRepostApplicationUrl():
    first = huiceJob(APPLY, "慧策集团")
    first["reqOther"] = f"投递邮箱：{EMAIL}"
    second = huiceJob("https://career.fudan.edu.cn/repost", "北京掌上先机网络科技有限公司")
    assert deduplicateJobRecords([first, second]) == [first]


def testBareApplicationDomainIsRecognizedInsideApplicationClause():
    first = huiceJob("https://example.test/a", "慧策集团", apply="")
    second = huiceJob("https://example.test/b", "慧策集团", apply="")
    first["reqOther"] = f"网申：zhaopin.huice.com/campus；邮箱：{EMAIL}"
    second["reqOther"] = f"投递地址：https://zhaopin.huice.com/campus；邮箱：{EMAIL}"
    result = deduplicateJobRecords([first, second])
    assert len(result) == 1


def testActiveJobIsNotBlockedOnlyByCrossSourceRecycledRow():
    recycled = huiceJob("https://example.test/a", "慧策集团", status="RECYCLED")
    incoming = huiceJob("https://example.test/b", "慧策集团", status="OFFLINE")
    assert JobDuplicateIndex([recycled]).find(incoming) is None


def testActiveJobIsNotBlockedByExactRecycledRow():
    recycled = huiceJob("https://example.test/a", "慧策集团", status="RECYCLED")
    incoming = huiceJob("https://example.test/a", "慧策集团", status="OFFLINE")
    assert JobDuplicateIndex([recycled]).find(incoming) is None


def testExactSourceCompanyPositionStillDeduplicatesWithoutApplicationEvidence():
    first = {
        "sourceUrl": "https://example.test/job",
        "companyName": "示例公司",
        "positionName": "编辑",
    }
    second = {**first, "jobDesc": "不同的模型表述"}
    assert deduplicateJobRecords([first, second]) == [second]


def testExactSourceKeyDoesNotOverrideExplicitCampaignConflict():
    first = huiceJob("https://example.test/job", "慧策集团", year="2027届")
    second = huiceJob("https://example.test/job", "慧策集团", year="2028届")
    assert deduplicateJobRecords([first, second]) == [first, second]
