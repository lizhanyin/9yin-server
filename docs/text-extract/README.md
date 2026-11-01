# 客户端文本资源知识抽取（text/chineses）

> 来源：`E:/Workspace/九阴服务端/resources/modern/text/chineses/`（客户端中文文本资源，共 44 个文件、约 90MB）。
> 抽取脚本：`tools/text-extract/extract_text.py`，重新生成：`python tools/text-extract/extract_text.py`。

## 源文件格式与编码

| 文件 | 编码 | 内容 | 规模 |
|---|---|---|---|
| `stringname.idres` | UTF-8+BOM | 物品/装备/秘籍等**名称** | 129,802 key |
| `stringname_npc.idres` | UTF-8+BOM | NPC/怪物/采集物**名称** | 87,563 key |
| `desc.idres` / `desc2` / `desc3` | UTF-8 | **物品描述**（desc2/desc3 同 key 覆盖 desc） | 148,016 / 108,754 / 2,866 |
| `string_buff_desc.idres` | UTF-8 | **状态效果**名称+描述（含等级后缀） | 106,149 key |
| `string_skill_desc.idres` | UTF-8 | **技能**名称+描述 | 77,480 key |
| `string_npctalk.idres` | UTF-8 | NPC 对话台词 | 35,533 key |
| `string_npcclientinfo.idres` | UTF-8 | 采集物图鉴（功效/部位/产地） | 984 key |
| `string_conditiondesc.idres` | UTF-8 | 条件门控描述（`desc_condition_NNNN`） | 7,105 key |
| `tips.idres` / `systeminfo.idres` | UTF-8 / UTF-16 | 玩法提示 / 系统消息模板 | 55,156 / 29,044 |
| `scene.idres` | UTF-8 | 场景与区域名（`area_NNNN`） | 2,719 key |
| `jiuyinzhi.idres` | UTF-8 | 九阴志（世界进程）界面文本 | 81 key |
| 其余 | — | 界面文本 interface、剧情 stringdrama、科举 ZZAnswer 等 | — |

格式均为 `key=value`，`//` 为注释；UTF-16 仅出现在 desc 早期版本误报（实际按 UTF-8 解析成功，脚本自动探测 utf-8-sig → utf-16 → gbk）。

## key 关联规则（勘察结论）

- **道具**：名称 `stringname:xxx` ↔ 描述 `desc:desc_xxx`（命中率 60,575/129,801）
- **Buff**：名称 `buf_xxx` / `buff_xxx` ↔ 描述 `desc_buf_xxx_<等级>`（等级后缀 `_0/_1/...` 表内功层数；存在 `desc_buf-wx_...` 连字符变体，匹配时 `-`≡`_`、忽略大小写）。命中率 28,746/28,819
- **技能**：`string_skill_desc` 内 `CS_xxx`（名称）↔ `desc_CS_xxx`（描述），合并后 59,959 条带描述
- **NPC 对话**：`string_npctalk:task_<小写npcKey>_<序号>`
- **采集物图鉴**：`story_/usepart_/planed_/usetype_` + `cj_NNNNNN` 四键一组

## 文本内嵌标记

- `{@N:参数名}`：运行时参数占位（如 `{@0:伤害包}`、`{@1:nameid}`、`{@0:color}`），服务端渲染时替换。
- `<font color="#xxxxxx">`、`<br>`：富文本着色/换行。
- 武学描述中的固定术语：**虚招/实招/招架（防反）/霸体/击飞/击倒/定身/封招/气竭**。

## 产出数据（docs/text-extract/data/）

| 文件 | 内容 | 行数 |
|---|---|---|
| `items.jsonl.gz` | 道具作用表：id/category/name/desc/params | 129,801 |
| `npcs.jsonl.gz` | NPC 作用表：id/kind/name/clientinfo/talk_sample | 87,559 |
| `buffs.jsonl.gz` | Buff 效果表：id/name/desc/params（数值解析） | 28,819 |
| `skills.jsonl.gz` | 技能效果表：id/category/name/desc/params | 77,405 |
| `gather_codex.jsonl.gz` | 采集物图鉴：story/usepart/planed/usetype | 179 |
| `talks.jsonl.gz` | NPC 对话全表 | 35,533 |
| `rules_numeric.json` | Buff/技能/道具数值分布统计 | — |
| `system_rules.json` | systemhelp/九阴志/error/触发事件/场景 原文 | — |

`params` 为从描述文本正则解析出的数值参数：`duration_sec`（持续秒）、`interval_sec`（间隔秒）、`layers`（层数）、`cooldown_sec`（冷却）、`hp_recover`（回血）、`damage_pct`（伤害%）、`prob_pct`（概率%）、`meter`（米）、`times`（次数）。

阅读方式（示例）：

```bash
python -c "
import gzip, json
for line in gzip.open('docs/text-extract/data/buffs.jsonl.gz','rt',encoding='utf-8'):
    r = json.loads(line)
    if r['id'].startswith('buf_ng_bh_001'): print(r['name'], r['desc'][:80])
"
```

## 伴生知识文档

- [道具作用分类](item-functions.md)
- [NPC 作用分类](npc-functions.md)
- [规则与数值数据](rules-and-data.md)
