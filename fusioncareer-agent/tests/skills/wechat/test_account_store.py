from pathlib import Path

from app.skills.business.wechat.store import WechatStore


def testAccountImport(tmp_path: Path):
    readFakeids = tmp_path / "gzh.txt"
    readNames = tmp_path / "公众号名字"
    readFakeids.write_text("fakeid-a\nfakeid-b\n", encoding="utf-8")
    readNames.write_text("手工名称\n", encoding="utf-8")
    readStore = WechatStore(tmp_path / "wechat.db")

    assert readStore.importAccounts(readFakeids, readNames) == 2
    assert readStore.importAccounts(readFakeids, readNames) == 2
    readAccounts = readStore.readAccounts()
    assert len(readAccounts) == 2
    assert readAccounts[0].name == "手工名称"
    assert readAccounts[0].nameSource == "MANUAL"
    assert readAccounts[1].name.startswith("account_")


def testAccountName(tmp_path: Path):
    readStore = WechatStore(tmp_path / "wechat.db")
    readStore.saveName("fakeid-a", "自动名称")
    assert readStore.readAccounts()[0].name == "自动名称"

    readStore.saveName("fakeid-a", "手工名称", True)
    readStore.saveName("fakeid-a", "后续自动名称")
    readAccount = readStore.readAccounts()[0]
    assert readAccount.name == "手工名称"
    assert readAccount.nameSource == "MANUAL"
