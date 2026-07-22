# DESIGN: P0 — 存储根目录修正

版本: v2.0
日期: 2026-07-22
status: design-ready

---

## 验收测试用例

### TC-P0-01：全新安装，文件功能正常

```
Given: Android 11+ 设备, 首次安装 MOV v4.0
When: 用户在房间内创建文件
Then:
  1. 文件写入 /sdcard/Android/data/com.hermes.android/files/mov/rooms/<id>/files/work/xxx.md
  2. 文件内容与写入内容一致（字节比对）
  3. 文件 tab 可正常浏览该文件
  4. 不弹出任何存储权限请求弹窗
```

### TC-P0-02：旧用户升级，数据自动迁移

```
Given: 设备上已安装 MOV v3.x, /sdcard/mov/ 下有 3 个房间的数据
When: 升级到 v4.0, 首次启动
Then:
  1. /sdcard/Android/data/com.hermes.android/files/mov/rooms/ 下出现相同的 3 个房间目录
  2. 所有文件内容与旧路径一致（逐字节比对）
  3. 房间列表不变, 聊天记录不变
  4. 旧路径 /sdcard/mov/ 保留不删
  5. 迁移完成后, device.json 写 migrated_storage_v4: true
  6. 迁移耗时 < 10 秒（200 个文件场景）
```

### TC-P0-03：重复启动不重复迁移

```
Given: 已完成 TC-P0-02
When: 再次启动 APP
Then:
  1. 迁移代码检测到 migrated_storage_v4: true → 跳过
  2. 无任何文件复制操作
  3. 启动耗时与正常启动一致
```

### TC-P0-04：迁移中途杀进程，下次启动恢复

```
Given: /sdcard/mov/ 下有 200 个文件, 迁移到第 100 个时 APP 被强杀
When: 再次启动 APP
Then:
  1. 迁移代码检测到迁移未完成 → 重新开始（全量重迁, 覆盖已迁的文件）
  2. 最终状态等同于一次性迁移完成
  3. 不出现"部分文件被迁移两次但内容错乱"的情况
```

### TC-P0-05：卸载 APP，数据自动清除

```
Given: MOV v4.0 已安装, 有 5 个房间的数据
When: 用户在系统设置中卸载 MOV
Then:
  1. /sdcard/Android/data/com.hermes.android/ 整个目录被系统删除
  2. 无残留文件
```

### TC-P0-06：旧路径不存在，跳过迁移

```
Given: 全新安装, /sdcard/mov/ 不存在
When: 首次启动 APP
Then:
  1. 迁移代码检测到旧路径不存在 → 跳过
  2. 不写 migrated_storage_v4 标记（因为不需要）
  3. APP 正常运行
```

---

## 实现约束（不可违反）

1. **必须用 `getExternalFilesDir(null)` 的返回值，禁止硬编码路径。** `StorageManager.BASE` 必须是运行时初始化的字段，不能是编译期常量。
2. **初始化必须在 `HermesApplication.onCreate()` 中完成。** 必须在任何文件操作之前。
3. **迁移必须在子线程执行，禁止主线程。** 用 `AsyncTask` 或 `Thread` + `Handler`。迁移期间 UI 可以展示进度（不强求，但不能 ANR）。
4. **迁移必须保证原子性。** 如果一个文件的复制失败，记录日志，继续复制剩余文件。最后汇总：X 个成功 / Y 个失败 / Z 个跳过。失败的文件不阻塞启动。
5. **`CapabilityExecutor.ROOMS_BASE` 必须从 `StorageManager` 获取，禁止自己维护第二份路径常量。**

---

## 状态生命周期

### 存储根路径

```
创建: HermesApplication.onCreate() → StorageManager.init(context)
       → BASE = getExternalFilesDir(null) + "/mov/"
       → 如果目录不存在 → mkdirs()
终结: APP 卸载 → 系统自动删除 getExternalFilesDir 整个目录
```

### 迁移标记 migrated_storage_v4

```
创建: 迁移成功 → device.json 写入 {"migrated_storage_v4": true}
终结: 无（永久保留，用于判断"迁移是否已完成"）
异常: 迁移中途失败 → 不写标记 → 下次启动重新迁移
```

---

## 改动清单

| 文件 | 改动 | 行数 |
|------|------|------|
| `StorageManager.java` | `BASE` 常量 → 静态字段 + `init(Context)` | ~15 |
| `CapabilityExecutor.java` | `ROOMS_BASE` 改为从 StorageManager 获取 | ~5 |
| `HermesApplication.java` | `onCreate()` 首行调 `StorageManager.init(this)` | 1 |
| `MigrationManager.java` | 新增迁移步骤: 递归复制 `/sdcard/mov/` → `getExternalFilesDir/mov/` | ~30 |
