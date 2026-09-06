import pytest

from app.skills.business.wechat.core import WechatApiError, get_articles


class FakeResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return {"base_resp": {"ret": 200013, "err_msg": "freq control"}}


class FakeSession:
    def get(self, *readArgs, **readOptions):
        readParams = readOptions["params"]
        assert readParams["sub_action"] == "list_ex"
        assert readParams["type"] == "101_1"
        assert readParams["free_publish_type"] == "1"
        return FakeResponse()


def testRaiseWechatApiError():
    with pytest.raises(WechatApiError, match="freq control"):
        get_articles(FakeSession(), "fakeid", "token", "cookie")
