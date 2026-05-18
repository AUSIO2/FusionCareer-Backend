"""BaseSkill — 所有 Skill 节点的抽象基类"""

from abc import ABC, abstractmethod
from typing import Any


class BaseSkill(ABC):
    """
    Skill 节点抽象基类。

    每个 Skill 需实现:
      - define(): 声明名称、描述、输入/输出槽位及类型
      - execute(inputs): 接收上游数据，返回输出数据
    """

    @abstractmethod
    def define(self) -> dict:
        """
        声明 Skill 的元数据和输入输出类型。

        返回格式:
            {
                "name": "skill_name",
                "description": "做什么的",
                "inputs":  {"slot_name": "type_string", ...},
                "outputs": {"slot_name": "type_string", ...},
            }
        """
        ...

    @abstractmethod
    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        """
        执行 Skill 逻辑。

        Args:
            inputs: {槽位名: 数据}，由引擎根据连线注入

        Returns:
            {槽位名: 数据}，对应 define() 中声明的 outputs
        """
        ...

    async def on_error(self, e: Exception) -> dict[str, Any]:
        """错误处理钩子，可覆写"""
        return {"error": str(e)}

    @property
    def name(self) -> str:
        return self.define()["name"]
