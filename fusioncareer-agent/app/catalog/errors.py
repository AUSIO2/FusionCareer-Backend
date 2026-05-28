"""数据类目录业务错误"""


class CatalogError(Exception):
    def __init__(
        self,
        message: str,
        *,
        code: str = "catalog_error",
        status_code: int = 409,
        referrers: list[str] | None = None,
    ):
        super().__init__(message)
        self.message = message
        self.code = code
        self.status_code = status_code
        self.referrers = referrers or []
