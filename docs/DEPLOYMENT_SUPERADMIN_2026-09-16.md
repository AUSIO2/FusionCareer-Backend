# 2026-09-16 超级管理员与用户管理上线

## 已发布

- Java 后端：172.22.130.216，容器 deploy-backend-1，端口 9100。
- 前端：10.107.13.184，经现有 Nginx 提供静态页面和 API 反代。
- 指定账号：25300130028（用户ID 2059808994040033282）从 ADMIN(1) 调整为 SUPERADMIN(2)，账号状态保持 NORMAL(1)。
- 本次只调整该账号角色；其他 11 名管理员、9 名普通用户保持原角色。

后端提供超级管理员权限、用户资料/简历查询、文件下载、Excel 导出和旧静态文件访问控制。
前端同步支持 SUPERADMIN 路由、仅超管可见的系统管理菜单、三种角色切换、用户资料弹窗、简历下载和资料表格导出。
前端代码位于 FusionCareer-View/ui_kits/student；既有依赖文件改动未回退。

## 版本

- 发布镜像：fusioncareer-backend:superadmin-20260916（正式 prod 指向此镜像）。
- 镜像ID：sha256:649ddb96a88e32617c588bc3476b9d819762ac52e1bd97abca9a04899b010b91。
- JAR SHA-256：8325a92ff81a83bebc6a9cf73cebc313de628041358a678ee8d9b262e727f14b。
- 前端入口：index-BwLMSQjg.js；管理页：AdminView-euyf6JM6.js。
- 前端 index.html SHA-256：8301302ad92610723a3be94391b1f812e00c7812aed2629d7b0bf1246cf36a7c。

## 验证

- 后端 80 项测试及打包通过；前端 4 项测试及 Vite 构建通过。
- 候选后端健康检查、新接口注册和匿名访问限制通过。
- 正式 /sys/health 及网关 /api/sys/health 返回 UP。
- 正式服务经内部鉴权读取指定账号，返回 role=SUPERADMIN，验证了数据库映射和运行版本。
- 正式系统管理接口、旧简历静态链接均拒绝匿名访问。
- 16 个已发布前端文件逐一校验内容哈希，与本地构建一致；静态资源读取权限已确认。
- 原简历文件数据卷、MySQL 和双向隧道保留；候选后端已清理。
- 未代替用户登录复旦 SSO。Java 重启后的旧会话需重新登录；前端刷新后显示新入口。

## 回滚

Java 发布目录：/data/fusioncareer/releases/20260916-superadmin。
保留 app-before.jar、backend-before.json、compose-before.yml、role-before.tsv 和 rollback.sh。
目录及包含环境信息的备份仅 root 可读。

Java 服务器执行：

```sh
/data/fusioncareer/releases/20260916-superadmin/rollback.sh
```

脚本恢复旧镜像，并将本次指定账号从 role=2 恢复为原 role=1；不修改其他账号和数据卷。

Python 网关服务器上的前端备份：
/home/vmadmin/fusioncareer/releases/20260916-superadmin-frontend/frontend-before.tar.gz。
旧 assets 保留，可将备份中的 dist/index.html 恢复到现有静态目录（文件权限 644），无需替换数据卷或重启 Nginx。
