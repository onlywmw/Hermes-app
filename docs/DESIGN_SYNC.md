# PLAN: 文档 & 代码同步

版本: v1.0
日期: 2026-07-22

---

## 问题清单

| # | 问题 | 现状 | 改法 |
|---|------|------|------|
| 1 | CLAUDE.md 引用 `DESIGN_BOARD_V1.md` | 文件不存在 | 改为 `DESIGN_BOARD_V2.md` |
| 2 | CLAUDE.md 引用 `DESIGN_FILE_FIXES.md` | 文件已被删除 | 删除引用 |
| 3 | CLAUDE.md 说 22 个桥方法 | 实际 30+ | 改为"30+ 个桥方法" |
| 4 | README.md 引用 `docs/HERMES_MASTER.md` | 文件叫 `MOV_MASTER.md` | 统一为 `MOV_MASTER.md` |
| 5 | strings.xml `app_name`: "MOV"，versionName: "1.1.0" | MOV_MASTER 写 v3.2 实际 v1.1.0 | 统一为 v3.2（versionName 1.1.0 → 3.2.0） |
| 6 | MOV_MASTER 写 v4.0 | 实际状态是 v3.2 | 统一为 v3.2 |
| 7 | DESIGN_RUNTIME 和 DESIGN_INTERACTION 对运行页架构描述冲突 | 一个说删 pid/JVM/通道/权限，一个说保留 | 以 DESIGN_RUNTIME 为准，DESIGN_INTERACTION 更新 |
| 8 | 删掉已完成的计划文档 | DESIGN_FILE_FIXES / PLAN_ROOM_V3 / DESIGN_BOARD_V1 | 删除或归档 |
| 9 | 包名 `com.hermes.android` vs 产品名 MOV | 代码不改包名，文档说明 | 在 MOV_MASTER 加一条:"包名保留 com.hermes.android 历史原因" |

---

## 执行清单

### 代码改

| 文件 | 改什么 |
|------|--------|
| `app/build.gradle` | `versionName "1.1.0"` → `"3.2.0"` |
| `app/src/main/res/values/strings.xml` | 无改动（app_name 已是 MOV） |
| `CLAUDE.md` | 修 1-3 号问题 |
| `README.md` | 修 4 号问题 |
| `docs/MOV_MASTER.md` | 修 5-6-9 号问题 |
| `docs/DESIGN_INTERACTION.md` | 修 7 号问题（运行页架构对齐 DESIGN_RUNTIME） |

### 删除

```
rm docs/DESIGN_BOARD_V1.md        # 已被 V2 取代
rm docs/DESIGN_FILE_FIXES.md      # 5 个修复已全部完成
rm docs/PLAN_ROOM_V3.md           # 实施计划已完成
```

### 包名说明

在所有设计文档头部或 MOV_MASTER 备注：

> 包名 `com.hermes.android` 保留历史原因。产品对外名称是 MOV。代码中的 HermesActivity/HermesBridge 等类名不改——改名风险大于收益。
