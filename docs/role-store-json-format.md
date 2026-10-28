# 角色存储 JSON 格式差异：Java 版与原生 C++ 版 roles.json 不兼容分析

> 本文整理自 2026-10-28 对 `data/roles.json` 解析报错的排查。结论：这不是 JSON 语法错误，
> 而是两代服务端写出的**顶层 schema 与角色条目字段结构完全不同**，Java 版 `JsonRoleStore`
> 从未实现过对原生存档的兼容读取；且 MySQL 双写兜底会在首次落盘时**整文件覆盖**原生存档。
>
> 相关代码：
> - `src/main/java/com/nineyin/db/JsonRoleStore.java`（JSON 读写）
> - `src/main/java/com/nineyin/db/MySqlRoleStore.java`（MySQL 主存储 + JSON 双写）
> - `src/main/java/com/nineyin/db/StoredRole.java`（Java 角色条目 schema）

---

## 一、背景：角色存储的三层结构

Java 版角色存储（`NineyinServer.openRoleStore()`）：

1. **MySQL 为主存储**（`MySqlRoleStore`，四表 `accounts` / `roles` / `role_appearances`
   / `role_locations`，表结构与 C++ migrations 完全一致，这一层两边**真正互为备份**）；
2. **JSON 文件为双写兜底**：`MySqlRoleStore.put()` 成功后调 `fileBackup.put()`
   （`MySqlRoleStore.java:150`），即同一个 `data/roles.json`；
3. MySQL 不可达时整体退回 `JsonRoleStore` 单后端。

问题出在第 2 层：原生 C++ 版也用 `data/roles.json` 存角色，但两边写的是两套格式。

## 二、现象

Java 服务端在原生版数据目录下启动时，日志报错但**不阻塞启动**：

```
ERROR com.nineyin.db.JsonRoleStore - role store load failed: data\roles.json
com.alibaba.fastjson.JSONException: syntax error, expect {, actual int, pos 15,
    fieldName version, fastjson-version 1.2.83
    at com.alibaba.fastjson.parser.deserializer.JavaBeanDeserializer.deserialze(...)
    ...
    at com.nineyin.db.JsonRoleStore.load(JsonRoleStore.java:75)
```

`role store backend: MySQL` 正常打印，服务继续跑——报错被 `load()` 内的 catch 吞掉
（`JsonRoleStore.java:83`），内存角色表为空，一切读写走 MySQL。

报错文案里的 "syntax error" 有误导性：文件本身是合法 JSON，是**结构不匹配**。

## 三、两边格式对照

### 1. 顶层结构

**Java 版 `save()` 写出的格式**（`JsonRoleStore.java:87`）——整个文件就是一个扁平 Map：

```json
{
  "acct:v1:xxxx": { "name": "...", "appearance": [...], "sceneConfig": "...", "...": 0 },
  "acct:v1:yyyy": { ... }
}
```

`load()`（`JsonRoleStore.java:75`）也按这个类型解析：

```java
Map<String, StoredRole> loaded = JSON.parseObject(
        json, new TypeReference<Map<String, StoredRole>>() {});
```

**原生 C++ 版实际格式**——顶层是带元数据的存档包装：

```json
{
  "version": 1,
  "accounts": {
    "acct:v1:...": { ...角色条目... }
  },
  "identities": { ... },
  "next_account_id": 6,
  "next_role_id": 6
}
```

（实测样本：`E:\Workspace\九阴服务端\data\roles.json`，5 个账号。）

### 2. 角色条目字段

| | 原生 C++ 格式 | Java `StoredRole` |
|---|---|---|
| 场景 | 嵌套对象 `"scene": {"config": "ini\\scene\\city05_ChengDu", "resource": "city05"}` | 扁平字段 `sceneConfig` / `sceneResource`（String） |
| 位置 | 嵌套对象 `"position": {"x": 695.26, "y": 23.01, "z": 669.24, "orient": 2.04}` | 扁平字段 `x` / `y` / `z` / `orient`（float） |
| 基础字段 | `name`、`appearance`（一致） | `name`、`appearance`（一致） |
| 扩展字段 | 20 来个：`power_level`、`level_title`、`nei_gong_points`、`next_stage`、`next_stage_points`、`justice`、`evil`、`free_base`、`character_flag`、`character_value`、`ene` / `max_ene` / `max_ene_add` / `resume_ene`、`sat` / `max_sat` / `max_sat_add`、`pk_value`、`drunk_level` | **无**（只有上述 8 个字段） |

原生多出的字段是完整的成长/状态数据（内功点、善恶值、精力、饱食度、PK 值、酒量等），
Java 端 schema 尚未建模。

## 四、根因分析

fastjson 拿 `Map<String, StoredRole>` 解析时，逐个读顶层键值对，把**每个 value**
当 `StoredRole`（JavaBean）反序列化：

1. 文件第一个顶层键是 `"version": 1`，值是 **int**，不是 `{` 开头的对象；
2. `StoredRole` 是 JavaBean，反序列化器期望 `{`，遇到 int 直接抛
   `expect {, actual int, fieldName version`；
3. `pos 15` 恰好是 `{\n  "version": 1` 中那个 `1` 的字节偏移——报错位置精确指向 version 的值。

即使未来跳过顶层包装、只取 `accounts` 子树，条目层面仍解析不了：
`StoredRole.sceneConfig` 是 String，fastjson 拿到 `{"config":...}` 对象同样会抛错
（`scene` / `position` 嵌套 vs 扁平是真障碍）；多出的 20 来个字段默认会被忽略，不报错但**静默丢数据**。

## 五、影响链（重要）

1. `load()` 失败被吞 → 内存表为空 → 读写走 MySQL，短期无害；
2. 但 MySQL 后端每次 `put()` / `updateLocation()` 都双写 JSON 兜底
   （`MySqlRoleStore.java:150` / `:241`）；
3. `JsonRoleStore.save()` 是**全量快照覆盖写**（`JsonRoleStore.java:87`）——
   第一次落盘就会用 Java 格式**整个覆盖** `data/roles.json`；
4. 原生存档中的 `identities`、`next_account_id` / `next_role_id`，以及 5 个账号的
   20 来个成长/状态字段（内功点、善恶值、精力、饱食度、PK 值等）**全部丢失**。

即：只要 Java 版在原生版数据目录里跑过一次登录/建角，原生存档即被破坏
（MySQL 四表是唯一还能对上的备份层）。

## 六、兼容方案（待实施，二选一或并存）

### 方案 A：`JsonRoleStore` 兼容读取原生格式

- `load()` 先探测顶层：有 `version` + `accounts` 键 → 按原生存档解析，取 `accounts` 子树；
- 条目做字段映射：`scene.config` → `sceneConfig`、`scene.resource` → `sceneResource`、
  `position.{x,y,z,orient}` → 扁平字段；多出的 20 来个字段要么给 `StoredRole` 建模（推荐，
  否则双写回原生目录还是丢数据），要么暂存 `Map<String, Object> extra` 原样带回；
- `save()` 保持写 Java 格式（或按探测到的来源格式写回），注意原生消费方（C++ 版）能否读
  Java 格式——若不能，写回时需保留原生包装结构。

### 方案 B：双写兜底改用独立文件名（最小改动）

- Java 版兜底文件改为 `data/roles.java.json` 之类，与原生 `data/roles.json` 物理隔离；
- 优点：零风险、一行配置的事；缺点：JSON 层面两边彻底不互通（备份语义只剩 MySQL）。

当前（2026-10-28）状态：两边共用 `data/roles.json`。**已验证**部署目录
`E:\Workspace\九阴服务端\data\roles.json` 仍为原生格式（5 个账号完好）——当日的冒烟测试
只启动服务、无客户端登录，未触发 `save()`。风险在**第一次有客户端登录/建角时**兑现，
届时恢复只能依赖 MySQL 四表或原生版自己的备份。

## 七、验证命令备忘

```bash
# 查看 roles.json 顶层结构与条目字段（Python）
python -c "
import json
d = json.load(open(r'E:\Workspace\九阴服务端\data\roles.json', encoding='utf-8'))
print('top keys:', list(d.keys()))
k = list(d['accounts'].keys())[0]
print('entry fields:', list(d['accounts'][k].keys()))
"

# MySQL 四表内容（与 C++ 互通的备份层）
mysql -h127.0.0.1 -P3306 -unineyin -pnineyin123 nineyin \
  -e "SELECT COUNT(*) FROM accounts; SELECT name FROM roles;"
```
