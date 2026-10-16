import pytest

from app.algorithms.job_structuring.chunking import (
    extractArticleContext,
    isLongOrCompilationArticle,
    splitArticleBlocks,
)


def assertCompleteOffsetCoverage(text, blocks):
    covered = 0
    for block in blocks:
        assert block.startOffset <= covered
        assert block.text == text[block.startOffset:block.endOffset]
        covered = max(covered, block.endOffset)
    assert covered == len(text)


def testSplitMarkdownByHeadingAndKeepTail():
    text = "# 某公司招聘\n报名邮箱：jobs@example.com\n\n## 产品经理\n" + "职责。" * 2500 + \
        "\n\n## 编辑\n负责内容编辑。"

    blocks = splitArticleBlocks(text, maxChars=3000, overlapChars=200, targetChars=2200)

    assert len(blocks) >= 3
    assert all(block.blockId for block in blocks)
    assert all(len(block.text) <= 3000 for block in blocks)
    assert any("产品经理" in block.text for block in blocks)
    assert any("负责内容编辑" in block.text for block in blocks)


def testSplitLongPlainTextRetainsBeginningAndEnd():
    text = "开头岗位。" + "招聘要求。" * 2000 + "最后岗位。"

    blocks = splitArticleBlocks(text, maxChars=2400, overlapChars=200, targetChars=1800)

    assert len(blocks) > 1
    assert blocks[0].text.startswith("开头岗位")
    assert blocks[-1].text.rstrip().endswith("最后岗位。")
    assert blocks[0].endOffset > blocks[1].startOffset


def testExtractCommonApplicationContext():
    text = "# 示例公司招聘\n公司介绍\n投递邮箱：jobs@example.com\n报名截止：2026-12-31\n## 编辑\n岗位职责"

    context = extractArticleContext(text, "https://example.test/job")

    assert context.title == "示例公司招聘"
    assert context.sourceUrl == "https://example.test/job"
    assert "jobs@example.com" in context.commonText
    assert "2026-12-31" in context.commonText


def testCompilationAlwaysUsesChunkedPath():
    assert isLongOrCompilationArticle("# 本周招聘岗位汇总\n内容", 6000)
    assert not isLongOrCompilationArticle("# 单岗位招聘\n内容", 6000)


@pytest.mark.parametrize("overlap", [0, 200, 400])
def testWindowAlignmentNeverSkipsTextPastHardEnd(overlap):
    text = "甲" * 3000 + "乙" * 100 + "\n" + "丙" * 3100 + "尾部岗位"
    blocks = splitArticleBlocks(text, maxChars=3000, overlapChars=overlap, targetChars=2200)

    assertCompleteOffsetCoverage(text, blocks)
    assert any("乙" * 100 in block.text for block in blocks)


@pytest.mark.parametrize("newline", ["\n", "\r\n", "\r"])
def testAllSourceOffsetsCoveredForMixedSemanticSections(newline):
    text = "\n\n# 招聘信息\n\n" + "岗位正文。" * 1600 + "\n\n## 其他岗位\n" + "没有句号" * 1400 + "\n尾部"
    text = text.replace("\n", newline)
    blocks = splitArticleBlocks(text, maxChars=2500, overlapChars=150, targetChars=1800)

    assertCompleteOffsetCoverage(text, blocks)


def testWhitespaceWindowsStillPreserveSourceOffsets():
    text = " " * 3200 + "\n## 编辑岗位\n采访和写作" + " " * 3200
    blocks = splitArticleBlocks(text, maxChars=2500, overlapChars=150, targetChars=1800)

    assertCompleteOffsetCoverage(text, blocks)
