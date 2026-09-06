import asyncio
import json
from pathlib import Path

from app.algorithms.resume_parser import parseText
from app.algorithms.resume_parser.extract import extractPdf
from app.algorithms.resume_parser.normalize import normalizeResume


READ_FIXTURES = Path(__file__).parents[1] / "fixtures" / "algorithm"


def writePdf(updatePath: Path) -> None:
    readStream = b"BT /F1 12 Tf 72 720 Td (Jane Doe) Tj ET"
    readObjects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(readStream)).encode() + b" >>\nstream\n" + readStream + b"\nendstream",
    ]
    createPdf = bytearray(b"%PDF-1.4\n")
    readOffsets = [0]
    for readIndex, readObject in enumerate(readObjects, 1):
        readOffsets.append(len(createPdf))
        createPdf.extend(f"{readIndex} 0 obj\n".encode() + readObject + b"\nendobj\n")
    readXref = len(createPdf)
    createPdf.extend(f"xref\n0 {len(readObjects) + 1}\n".encode())
    createPdf.extend(b"0000000000 65535 f \n")
    for readOffset in readOffsets[1:]:
        createPdf.extend(f"{readOffset:010d} 00000 n \n".encode())
    createPdf.extend(
        f"trailer\n<< /Size {len(readObjects) + 1} /Root 1 0 R >>\nstartxref\n{readXref}\n%%EOF\n".encode()
    )
    updatePath.write_bytes(createPdf)


def testExtractPdf(tmp_path: Path):
    readPath = tmp_path / "resume.pdf"
    writePdf(readPath)
    assert "Jane Doe" in extractPdf(readPath)


def testNormalizeResume():
    readContract = json.loads((READ_FIXTURES / "resume_contract.json").read_text(encoding="utf-8"))
    readRaw = {
        "real_name": "张同学", "gender": 2, "political_status": 2,
        "grade": "2023级", "major": "新闻传播学", "edu_level": 2,
        "intention_city": ["上海", "北京"],
        "education": "复旦大学；新闻传播学；学术型硕士；2023-09至今",
        "internship": "示例新闻社；新媒体实习生；选题策划与数据复盘",
        "skills": "采访写作；Premiere；Python",
    }
    assert normalizeResume(readRaw) == readContract


def testParseText():
    class FakeResumeClient:
        async def chat_json(self, **readOptions):
            return {"profilePatch": {"realName": "张同学"}, "resumePatch": {"skills": "Python"}}

    readResult = asyncio.run(parseText("姓名：张同学", FakeResumeClient()))
    assert readResult["profilePatch"]["realName"] == "张同学"
    assert readResult["resumePatch"]["skills"] == "Python"
