# 采集模块

官网和公众号共享存储、正文转换和岗位结构化；来源特有的抓取逻辑分别放在 official/、wechat/。

```text
crawlers/
  paths.py                CrawlPaths、采集目录解析
  store.py                CrawlStore、文章状态、去重和运行记录
  io.py                   HTTP 会话、JSON/JSONL 文件读写
  content.py              时间、文件名、HTML → Markdown
  structure_articles.py   共享岗位结构化与草稿入库
  official/
    runner.py             官网抓取编排、OfficialCrawlSitesSkill
    common.py             HTML 解码、日期、限流重试
    html_lists.py         HTML 列表、分页、正文节点定位
    api_lists.py          高校 JSON 列表接口
    archive.py            正文下载、Markdown 归档、manifest
  wechat/                 公众号接口与单号抓取工作流节点
```

官网站点清单仍位于 app/presets/official_sources.json，工作流仍为 official_daily。
各 Skill 的注册名称保持兼容（包括 wechat_structure_articles 和 official_structure_articles），
已保存的工作流与 Scheduler 配置无需改名。

新的数据目录配置名为 CRAWL_CONFIG_ROOT；未配置时继续使用 WECHAT_CONFIG_ROOT。
节点显式传入 paths.config_root/configRoot 时优先使用该路径。
新目录使用 crawl.db；目录里只有旧 wechat.db 时原地复用，不复制或迁移数据库。
已有公众号文章目录、官网文章目录、manifest 和 Docker 数据卷保持兼容。

此处重构不修改 app/vendor/fusioncareer_algorithm 中固定版本的上游代码。
