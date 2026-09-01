# 微信公众号爬虫（Agent Skill + For Loop）

## 数据目录

在 `WECHAT_CONFIG_ROOT`（或请求 `overrides` 的 `paths.json_obj.config_root`）下保留：

| 文件 | 说明 |
|------|------|
| `config.json` | `articles_base_dir` 等非密钥配置 |
| `wechat.db` | 公众号、文章、断点和运行状态 |
| `daily_report.jsonl` | 每次 daily 汇总行 |
| `manifest/manifest.jsonl` | 供后续 Parser workflow 扫描 |
| `公众号文章/` | Markdown 主存档 |
| `YYYYMMDD新增/` | 当日增量镜像（daily） |

`gzh.txt` 和 `公众号名字` 只在首次启动时导入 SQLite，之后不再按行关联。公众号名会从文章 HTML 自动更新，人工名称优先。

Cookie/Token 通过 `WECHAT_COOKIE` 和 `WECHAT_TOKEN` 注入，Agent 不负责登录。为兼容一次性迁移，未配环境变量时仍可从本地 `config.json` 读取。

## Daily（HTTP）

```http
POST /api/workflows/wechat_daily_body/run
X-Agent-Admin-Token: <token>
Content-Type: application/json

{
  "overrides": {
    "paths.json_obj": { "config_root": "/data/wechat" }
  },
  "loop": {
    "judge_skill": "wechat_judge_accounts",
    "max_iterations": 500,
    "judge_inputs": {},
    "initial_globals": { "stats": {} },
    "finalize_skill": "wechat_finalize_daily",
    "finalize_inputs": {
      "paths": { "config_root": "/data/wechat" }
    }
  }
}
```

外层 for：每轮 `iteration` = 一个公众号索引；`wechat_process_account_daily` 内部处理分页与逐篇下载。

## Bootstrap

```http
POST /api/workflows/wechat_bootstrap_body/run
```

同样带 `loop`（`judge_skill: wechat_judge_accounts`），无需 `finalize_skill`。

## 定时任务

```http
PUT /api/admin/schedules/wechat-daily
{
  "id": "wechat-daily",
  "workflow": "wechat_daily_body",
  "enabled": true,
  "trigger": { "type": "cron", "cron": "0 17 * * *" },
  "overrides": {
    "paths.json_obj": { "config_root": "/data/wechat" }
  },
  "loop": {
    "judge_skill": "wechat_judge_accounts",
    "max_iterations": 500,
    "initial_globals": { "stats": {} },
    "finalize_skill": "wechat_finalize_daily",
    "finalize_inputs": {
      "paths": { "config_root": "/data/wechat" }
    }
  }
}
```

## 与 pipeline.sh 对照

| pipeline.sh | Agent |
|-------------|--------|
| `bootstrap` | `wechat_bootstrap_body` + `loop` |
| `daily` | `wechat_daily_body` + `loop` + `finalize_skill` |
| `install-cron` | Admin schedule API |
| `sync-session` | 仍用原脚本更新 cookie |
