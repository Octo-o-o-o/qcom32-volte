# 研发归档与历史试错索引 (`archive/`)

历史索引中涉及本项目的 “open-source” 旧称均指源码可见（PolyForm Noncommercial 1.0.0 + 商业授权），不代表开放源代码许可。

本目录专门用于归档 BlackBerry Passport (`msm8974` / LineageOS 18.1 Android 11) 原生 VoLTE 攻关过程中**所有踩坑、被排除的路线、历史原型与取证工具分类索引**，实现**正式生产目录与历史探索记录的严格分离**。

## 1. 正式目录 vs 归档目录结构说明

| 目录 | 定位 | 内容说明 |
| --- | --- | --- |
| [`src/`](../src/) | **正式生产源码** | **仅包含最终实机验收通过的 3 个核心组件**：<br>1. `src/android-ims-service/` (`PassportVolteIms.apk` v2.3)<br>2. `src/modem-efs-provisioner/` (`pp_regconfig_provision_v6`)<br>3. `src/qmi-imsa-probe/` (`libimsa_probe.so`) |
| [`cli/`](../cli/) | **正式构建与管理 CLI** | 零第三方依赖的源码可见构建脚本 `build_all.py`、一键部署/状态/回滚工具 `volte_ctl.py`、源码可见仓库导出工具 `export_opensource_bundle.py` |
| [`docs/guide/`](../docs/guide/) | **源码可见工程文档** | 系统架构说明、安装与回滚指南、产品形态选型（刷机包 vs App vs 桌面端）与换卡机制详解 |
| `archive/` | **踩坑复盘与历史索引** | [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md)（核心踩坑技术复盘）与 [`catalog.json`](catalog.json)（75 个历史原型目录与 660+ 个研发脚本的全量分类清单） |

## 2. 为什么研究主仓保留冻结路径，同时提供一键纯净导出？

在本研究主仓库（`Octo-o-o-o/bb-passport-android11-volte`）中，`evidence/debug-journal.jsonl` 与 `tools/index_debug_history.py` 对全部 1,920+ 条实验记录、75 个 `prototype/` 子目录及 `docs/01..111` 报告实施了严格的 SHA-256 哈希锚定（用于学术/工程级不可篡改取证与随时回滚核验）。

如果你希望推送到**对外公开的全新 GitHub 源码可见仓库**（不包含 `prototype/`、`tools/`、`evidence/` 等研发过程文件），只需运行：

```bash
python3 cli/export_opensource_bundle.py
```

该命令会在 `dist/bb-passport-android11-volte-opensource/` 生成一个**源码可见（PolyForm Noncommercial 1.0.0 + 商业授权）**的标准仓库目录（仅保留 `README.md`、`LICENSE`、`.gitignore`、`src/`、`cli/`、`docs/guide/` 与 `archive/` 踩坑总结，并自动执行密钥与隐私数字扫描）。
