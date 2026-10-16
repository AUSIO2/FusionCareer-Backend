from app.algorithms.job_filter import filter_jobs, should_keep_job


def testKeepCommunicationJob():
    readKeep, _ = should_keep_job({
        "positionName": "新媒体编辑",
        "reqMajor": "新闻传播学",
    })

    assert readKeep is True


def testDropTechnicalJob():
    readKeep, _ = should_keep_job({
        "positionName": "Java开发工程师",
        "reqMajor": "计算机科学与技术",
    })

    assert readKeep is False


def testFilterJobs():
    readKept, readDropped = filter_jobs([
        {"positionName": "品牌传播", "reqMajor": ""},
        {"positionName": "算法工程师", "reqMajor": "软件工程"},
    ])

    assert [readJob["positionName"] for readJob in readKept] == ["品牌传播"]
    assert readDropped[0]["_filterReason"]


def testKeepManagementTraineeUnlessClearlyTechnical():
    assert should_keep_job({"positionName": "管理培训生", "reqMajor": "专业不限"})[0] is True
    assert should_keep_job({"positionName": "技术管培生", "reqMajor": "计算机"})[0] is False
