# msg31 道具使用效果链（宝箱开启/使用物）——探索取证与实现

2026-11-04 整理。范围：客户端右键使用的完整语义、宝箱类物品的数据模型与掉落包全景、
参考实现盘点、本服实现决策记录。契约脚本层见
[skill-script-framework.md](skill-script-framework.md) §item；帧格式见
[go-source-protocol-notes.md](go-source-protocol-notes.md) §7.3。

## 1. 客户端侧语义（F:\Game\Online\9yin\九阴资源\lua64_unpacked 取证）

右键使用链路（`lua64\form_stage_main\form_bag_func.lua`）：

- `on_bag_right_click(grid, index)`（:309）先分流特殊 UI（仓库/摆摊/寻宝修复/合成、
  `do_color_egg`、`do_bind`），书本类开预览，其余走 `confirm_use_item(item)`（:1372，
  仅特定类型弹确认：ITEMTYPE_COMPOSE_LILIANYAO、FuncPack==8201 寻宝、带 AddBufferList）。
- 最终都汇到 **`GoodsGrid:ViewGridUseItem(grid)`**（:479/483）——GoodsGrid 是 C++
  引擎对象（`nx_value("GoodsGrid")`），不在 Lua 层定义。
- 绑定后使用（`form_itembind\form_itembind_use.lua:33`）、新背包 UI
  （`form_bag_new_func.lua:183` / `use_item_by_configid:111`）同一出口。
- Shift 连用：`GoodsGrid:IsRepeatedlyUsedItem`（名单在
  `resources\share\item\item_using_repeatedly.ini`）+ 输入框写
  `grid.times_for_using_item_repeatedly` 后同口重放。

**上行 = 原生 msg31（非 custom，`lua64-protocol-dict.md` 里查不到）**：恰好 4 值
`[31, view(int), slot(int), count(int≥1)]`；3/5 值或 count=0 拒绝。出处三重钉死：
menu2.exe 字符串 `reject malformed native item use values=` + `USEITEM fwz card`
（go-source-protocol-notes.md §7.3 :250）、本服 LoginFlow.handleBodyItemUse 实测、
bak 服 useItem 行为。

**下行预期：普通宝箱（锦盒/礼包）没有任何专用握手——纯服务端驱动**。客户端只认：

- 背包行帧 `0x18 ViewAdd` / `0x19 ViewRemove`（视图表 `lua64\share\view_define.lua`；
  背包相关 VIEWPORT_EQUIP=1 / VIEWPORT_TOOL=2 / VIEWPORT_EQUIP_TOOL=121 /
  BOX_CHOICES=155 / DROP_BOX=80 / TREASUREBOX=158）；
- 刷新胶水 `goods_grid.lua` 的 `view_grid_on_update_item/on_delete_item` →
  引擎 `ViewUpdateItem/ViewDeleteItem`；
- 可选文字反馈走命令名寻址 custom 下行 `custom_specific_sysinfo/custom_sysinfo`
  （`custom_handler.lua:87/:264`，tips_type 路由系统条/聊天/居中）。

存在但**均非普通背包宝箱**的箱类 UI（后续做选择箱时才碰）：

| UI | 视图/消息 | lua |
|---|---|---|
| 选择箱（N 选 1） | view 155 物化候选 + C2S 389 `custom_choice(2, pos)` 选取 / `custom_choice(1)` 取消 | `form_pick\form_choices.lua`（容量 16） |
| 寻宝宝箱收集 | S2C `custom_treasure_chest` + C2S 638 | `form_treasure_chest.lua` |
| 运营奖品箱 | S2C `custom_box_event` + C2S 713 | `form_box_event_prize.lua` |
| 邮件箱 | C2S 350（复合箱 603-607） | — |
| 掉落拾取 | view 80 + C2S 380-382 | `form_droppick` |

GM 相关：lua64 无发物品 GM 链（仅 custom_gminfo/msg2）；`custom_give_item`
（msg 80）是**给 NPC 交任务物品**，勿混淆。giveitem 发箱的真源是 NPC optiontalk：
`Option=giveitem|<选项>|Box_qycdu_00N*1|11285|3600|||1`（`share\rule\optiontalk_dialog_pack.ini`，
参数=物品*数量 | sysinfo 11285 | 选项冷却 3600s）。

## 2. 物品数据模型（share/item/tool_item.ini，GBK ~1.2M 行）

- 节名即 configId（**无数值 ID**），键大小写不敏感；grep 必须带 `-a`（GBK 会被当二进制）。
- **`script=` 是行为类**（服务端语义分发键）：SkillPage ~24.8k、ToolItem ~20.1k、
  **BoxItem ~14.7k**、SkillBook ~6.1k、CardItem ~1.1k、WuJiItem ~1.1k、
  **CompensateItem 744**、ExchangeItem ~0.5k（两份 G 拷贝与 F 运行拷贝条数略有出入）。
- `ItemType=` 数值类（`lua64\share\itemtype_define.lua`）：2102=GIVENBOXITEM 礼盒、
  2103=BOXITEM 包裹、2159=奇遇锦盒、34=WUJI_BOX、68=FIND_TREASURE、2156=COLOR_EGG。
  客户端侧"箱性"=ItemType 2102/2103；无 UseType/EffectType 字段。

**BoxItem**（开包类）：`DropID=` 直接指向掉落包名，且惯例**自指**——
`[qy_chengdu_009] Name=包裹 script=BoxItem ItemType=2102 DropID=qy_chengdu_009`
（G 拷贝 :214960 / 9yin_server_new 拷贝 :219765）。

**CompensateItem**（奇遇锦盒类，Box_qycdu_009 真身，:355418）：

```ini
[Box_qycdu_009]
Name=成都奇遇
ItemType=2159
script=CompensateItem
@CompensateItemRec=18658,qy_chengdu_009
@CompensateItemRec=18659,box_qycdu_009
```

- `@CompensateItemRec=<条件Id>,<ref>` 重复键逐行列出奖励引用；Box_qycdu_001..011 均为
  双 ref（18658→qy_chengdu_NNN 奖励包 + 18659→box_qycdu_NNN），016 单 ref。
- **ref 两种落点**：`qy_chengdu_009` 有同名 BoxItem 节（DropID 自指）；
  `box_qycdu_009` 无节、纯掉落包名。因自指惯例，统一"ref 直接当 dropId"即可。
- 条件门控（`share\rule\condition.ini` :167060）：`[18658] Type=19 Func Para1=56
  Para2="==" Para3=2`、`[18659] Para2="<>" Para3=3 Para4=9` —— **玩家 func-56 状态**
  决定走哪个分支（==2 发奖励包；∉3..9 发箱包；本服未接该状态，见 §5 简化）。

**giftbox.ini**（项目自建 UTF-8 表，服务端识别 `script=BoxItem` 用 DropID 指向
drops_mvp.json；仅 `[box_chinajoy_2026_giftbox]` 一条，含 UseCondition=17545/DropBind/过期）。
**item_use_skill_static.ini**（GBK）：`[itemType] SkillID/SkillLevel`——2101→yaopinhp1 L1、
2102→L2、2103→L3、2104→L4；yaoshihp L1-10 在 [11]-[21]；dushiyb [2007]。药品=使用技能。

## 3. 掉落包数据全景（谁在什么位置存奖励）

| 数据源 | 位置 | 形态 | 消费方 |
|---|---|---|---|
| **drops_mvp.json** | `share\drop\`（go-server1 + F:×3，四副本须 md5 全等） | `packages.{id}=[{item,min,max,weight}]`，头注释自述"MVP 自制包承接原版缺失包体"；现 5328 包 | **本服开包唯一权威**（DropPacks） |
| drop_table.json | 9yin_server_public / 9yin_server1 的 `share\item\` | `{"<DropID>":{mode:random|fixed, rolls, entries}}` | C++ 参考树，Java 未用 |
| giftbox.json → MySQL game_gift_boxes | go-server1 `data\`（322/764 箱） | 固定**全发**（weight 无意义） | 仅 gm-web 面板 |
| 道具配置.json → game_item_effects | data-import | heal_hp/heal_mp/consume/effects | 已导入未消费（面板） |

**缺口事实**（2026-11-04 探索钉死）：`qy_chengdu_009`/`box_qycdu_009` 包体在所有
数据源中**原本均不存在**（`qycdu` 全库 0 命中；qy_ 前缀仅 105 包，成都系只有
qy_chengdu_021/022）——官方包体从未随资源发布。本次按 qy_chengdu_021/chinajoy
形制 authored 补齐（yaopin_00100/00300/00500/00700 权重阶梯，箱包偏高档），
四副本 md5 `0d55ea8b…` 全等。**9yin_server_new 原本连 share\drop\ 目录都没有**
（giftbox.ini 也缺，本次补拷；item_use_skill_static.ini 原有）。

## 4. 参考实现盘点

- **bak 树 = 唯一完整参考**（本次移植源）：`9yin-server-bak\...\game\SceneFlow.java:1950`
  `useItem()` 五步（节流→Lua 优先→INI 回退→效果落账→消耗帧对）+
  `ItemUseCatalog` / `DropPacks` / `script\ItemScriptBridge`（指令白名单解析）。
- **Go 活体 exe（cmd/protocol-probe）没有 msg31/30/886-889**——只做登录/心跳/技能/
  快捷栏/打坐/格挡等 custom 面；`OpenBoxFunc`（names_gen.go:1019）只是 NPC 规则
  属性（GivingNpc.xml 世界箱），与背包物品无关。野外开箱在 bak 是 CloneLoot
  （boxnpc NPC + S2C 0x05 worldInfo 文字）。
- **两个"官服"zip 均非官服**：`九阴服务端.zip`（31,546 条目）= go-server1 工作树快照；
  `野猪端.zip`（11,824 条目）= 更旧 Go 树 + mysql + 抓包。全库 0 个服务端 .lua。
  官方 BoxItem.lua/CompensateItem.lua 在磁盘上不可得，语义只能从数据逆推。

## 5. 本服实现（2026-11-04，分支 9yin-main-new）

解析优先级与指令白名单见 skill-script-framework.md §item。要点与决策记录：

- 接线点 = `LoginFlow.handleBodyItemUse` 不可穿戴/非卡片分支 → `useItemEffect`
  （五步对齐 bak）。`consumeBagCard` 改名 `consumeBagItemOne` 通用化（逻辑零改）。
- 新包 `server.game.item`：`ItemUseCatalog`（giftbox.ini + tool_item.ini 只捕
  script/DropID/@CompensateItemRec 三键 + item_use_skill_static 阶梯；ResourceCache.of
  键加前缀防撞）、`DropPacks`（JSON 解析器自带；roll 抽 1 条/次、数量 min..max、
  同物品合并、rolls 钳 1..10）。
- `LuaScriptService.itemUse`：仿 skillOverlay 惯例（ItemModule_ 全局包裹、缓存+NONE
  哨兵、异常 markBroken 返 null 走 INI）。**返回 null=无脚本/nil/引擎坏**——
  与 bak 同语义，使用链永不拒绝。
- **简化决策**：
  - CompensateItem func-56 条件门控未接 → **ref 全发**（单机服口径，日志可追）；
  - temp_stat 已解析未实施（面板限时属性二期）——**仅剩 temp_stat 效果时不消耗防白吃**；
  - grants 部分失败（背包满）仍走消耗（bak 同款，防"半发+不消耗"重复刷取）；
  - 脚本 open_box 抽空包 → 提前返回不消耗（对 bak 的收紧，防 ScriptLock 白吃）；
  - 不发 sysinfo（与 GM 宝箱/发券现状一致，帧即反馈）。
- 节流：会话级 800ms（LoginSession.lastItemUseMs）；随机数取会话 combatRandom（可换种子）。
- 测试：`DropPacksTest`/`ItemUseCatalogTest`/`LuaItemUseTest`/`ItemUseFlowTest`
  15/15；FlowTest 走真 MySQL 临时角色 990033 全链（消耗→入包 DB 行+0x18 帧→重复拒）。
- 部署：jar 20:00 版已上 9yin_server_new（旧份备份 .bak-20261104-2005）；
  scripts/item 三个锚点原已镜像。

## 6. 真机首验根因与缺包兜底（2026-11-04 晚，已修已部署）

真机现象：「物品使用后没有生效」。取证（logs/nineyin-java.log）：

- 链路本身**健康**——21:55–22:01 多次成功：`box_item_xiakedao_haidibaoxiang_3`
  授予 yaopin_00100、`box_ding_0606xsh001` 授予 yaopin_00700×2、ng_book/item_ttf
  走 USE_SKILL 回血消耗；21:58 卡片收录链同源工作。
- 失败集中在 22:05 的 `box_20220401_mpwjb_fc_001`（门派武技包，role618 slot2，
  连点 20 次）——`drop packs [comd_drop_box_20220401_mpwjb_fc_001] empty/missing`：
  **msg31 已到、目录已判箱，但 DropID 引用的包体不在 drops_mvp.json → roll 空返 →
  提前 return 不消耗零帧**（当时设计防白吃，代价是静默）。
- 缺口全景（deployed tool_item.ini × drops_mvp.json diff 钉死）：箱类 15,232 节引用
  **16,903 个缺失包**（官方包体从未随资源发布，5,328 authored 包只覆盖零头）；
  giftbox.json（322 箱）/game_item_effects 均无这些包体。排查中的两个**红鲱鱼**记录：
  ① DB 旧种子行 item_type=0/script NULL——`bagItems()` 读路径的 game_items 富化层
  已经在内存补正（0x10/0x18 帧携带正确 ItemType），**线上无影响**；
  ② grep 窗口渗入邻节造成的「无 script= 行」误判（python 整节解析全都有 script=BoxItem）。

**修复（DropPacks.java，22:35 部署 9yin_server_new）**：缺包 roll 不再静默空返，改走
`FALLBACK_LADDER`（yaopin_00100/00300/00500/00700 权重阶梯 50/30/15/5，形制对齐
qy_chengdu 系 authored 补齐）并 INFO `drop pack {} missing, rolling fallback ladder`
可追；`has()` 仍只认 authored 包。空 dropId / 缺文件容错不变。任何箱现在都可开
（缺包箱给阶梯药），测试：`DropPacksTest.unknownDropIdFallsBackToLadderAndMissingFileTolerated`
+ `ItemUseFlowTest.missingDropPackFallsBackToLadderAndStillOpens`（真 MySQL 缺包箱
开包/消耗/掉落全链）。

## 7. 待办与二期钩子

- func-56 玩家状态接入后，CompensateItem 收敛回条件单分支；
- 选择箱（view 155 + msg 389 `custom_choice`，容量 16）——需要 N 选 1 礼盒时做；
- `custom_specific_sysinfo` 文字播报"获得物品"（官方 11285 等 string_id 无 dump，需抓包）；
- temp_stat 面板限时属性（同源替换 + tick 过期 + 面板重算）；
- UseCondition（如 chinajoy 17545）/ValidTime 过期校验目前忽略。

## 8. 坑位备忘

- repo 的 `resources/` 是 gitignored 且**不在仓库内**——测试一律
  `ServerPaths.overrideServerRoot(GO_ROOT)` 指向 go-server1；LuaScriptService 单例可能在
  模块 CWD 下初始化成 broken（formula 缺失），测试类 @BeforeAll 须 override + `reload()` 自愈。
- GBK ini grep 加 `-a`；iconv 大文件遇非法字节静默截断（用 python 整读）。
- drops_mvp.json 改动必须四副本同步并 md5 校验（9yin_server_new 的 drop 目录是
  2026-11-04 新建，历史上该 SERVER_ROOT 无任何掉落数据）。
- role618 金丝雀（RoleInventoryStoreIntegrationTest）按当期 DB 对齐，真机穿脱即漂移：
  2026-11-04 晚 = 穿戴 8/背包 34/武学 190/内功 48/快捷栏 117/扩容 4。
