# menu2.exe GM 命令分析 与 gm-live 面板动作修复（2026-10-31）

对象：`F:\Game\Online\9yin\9yin_server1\build\9yin-game-native-menu2.exe`（2025-10-16 构建，
Go 编译，符号完整；比 9 月 19 日的 menu.exe 新一代）。gm-web `console.html` 即该 exe 内嵌
面板（kIndexHtml，79.6KB）的同源版本，故**面板按钮 ≈ 本服后端应支持的动作全集**。

## 1. 链路（menu2 侧）

```
网页面板 console.html (79.6KB, 19 个 /api/* 路由, 64 个 action)
  → main.serveGM.func20  "GM API command received: session=%d action=%q"
  → gmHub.enqueue (队列)
  → 游戏主循环 main.handle.func17 (65KB 巨型 switch, ~90 case)   ← 本次逆向主体
  → 各 helper（setXxx + 帧构造 + WriteFrame + persist）→ writeGMReply 回执
```

参数 JSON 结构（Go 反射串完整 dump）：action/silver/guanYin/gold/sp/value/stage/eventKind/
destination/preset/position/faction/cloneConfig/itemConfig/amount/wearNow/cardId/titleId/taskId/
speechText/speechMode/justice/evil/freeBase/ene/maxEne/maxEneAdd/resumeEne/sat/maxSat/maxSatAdd/
pkValue/drunkLevel/jingMaiId/jingMaiTier/jingMaiLevel/msgId/subType/teamArgs/keyArgs/bingluArgs/
dummyHp/mountConfig/weather/attrStr/attrSta/attrDex/attrIng/attrSpi/slot/packIDs/affixKind/
weaponType/crafter/armorSlot/stanceAction/dummyName/dummySkills/dummyDamage/pkMode/reputeName/
reputeMode/reputeValue

另有聊天框文本命令 `main.executeGMCommand`（help/info/additem/gold/guanyin/silver/gm/木桩/宝箱，
中文命令名支持）与 `main.handleGMCustom`（GM 等级鉴权，gmPermissionStore 持久化）。

## 2. 关键坑：属性 ID 两套表不可直抄

menu2 的属性帧走 `sceneObjectProperties`（0x10 | u8 view | u32 oid | u32 owner | u16 count |
条目{u16 propID + 类型值}，与 Java `S2CPackets.Batch` 同构），但 **propID 数字与本服
0x09 表（official_property_table.txt，2424 条，登录下发）不同版本**：

| 属性 | menu2 ID | 本服 index | 类型 |
|---|---|---|---|
| CapitalType0 黄金 | 309 | **415** | i64 |
| CapitalType1 碎银 | ? | 416 | i64 |
| CapitalType2 官银 | 357 | **417** | i64 |
| PKMode | 363 | **436** | u8 |
| HasLearnExtSkill | 364 | **434** | u8 |
| TitleID | ? | **359** | i32 |

规律：非整体偏移（309→415 差 106、357→417 差 60、363→436 差 73），**必须按属性名到
本服表查 index**。已全部换算进 `PropertyId.java`（新增 21 个常量）。

## 3. 帧语义备忘（逆向所得）

- 气泡/聊天：`0x1A | u32 oid | u32 (频道 或 字节长) | UTF-16LE | u16 0`
  （空终止+频道=serverSpeechNullTerm=新式；长度前缀=serverSpeech=旧式）→ `CustomPackets.speechNullTerm/speechLenPrefix`
- PK 模式帧：PKMode(436,u8) + HasLearnExtSkill(434,u8=0) 同帧
- 实力等级：PowerLevel(210,i32)；称号名映射 title001..title027（≤5→001，每 5 级 +1 至
  120→024，121-130→025，131-150→026，>150→027）
- GM 回执通道：`serverCustomIntMessageWithOpcode(30, 377, …)`（popup_gm_panel 用
  "__gm_popup__" 让客户端弹面板；Java 侧待做）
- 宝箱：`spawnChestAtPlayer` → config `Boxnpc10006`，玩家面前 2m（sin/cos 朝向）
- 飘字：`writeFightFloat(1, 17, 1, 1, 24680)`（日志「已发送伤害飘字测试 (ID24, 24680)」）
- setVitals 9 属性：Ene/MaxEne/ResumeEne/Sat/MaxSat/DrunkLevel/PKValue 在本服表内
  （704/705/706/707/708/722/426）；maxEneAdd/maxSatAdd 本服表无同名 → 忽略

## 4. 动作三态表（面板 64 action）

### 4.1 本次补齐（第一批，15 个，帧语义 100% 确定）

| action | 实现 |
|---|---|
| set_guanyin | actor.guanYin + 417 i64 帧 |
| set_vitals | Ene/MaxEne/ResumeEne/Sat/MaxSat/DrunkLevel/PKValue 按需 i32 批量帧 |
| set_alignment | Justice(320)/Evil(321)/FreeBase(322) i32 批量 |
| set_five_attributes | Str(484)/Sta(488)/Dex(486)/Ing(485)/Spi(487) + vitalUpdate |
| set_power_level | PowerLevel(210) + title001..027 称号名 |
| set_pk_mode | PKMode(436,u8)+HasLearnExtSkill(434,u8=0)，0~3=和平/江湖/阵营/戕屠 |
| grant_title / wear_title / cancel_title / clear_titles | session.grantedTitles/wornTitleId + TitleID(359) 帧 |
| speech_nul / speech_len / chat_broadcast | 0x1A 帧（speechMode 默认频道 1） |
| add_item / grant_item | SceneFlow.giveItem（grant_item.wearNow 即穿暂缓） |
| spawn_chest | Boxnpc10006 NPC 玩家身旁（tag=gm_chest） |
| idle_stance_set | State 帧（idle_stance_ 前缀剥离） |
| custom_probe | CustomPackets.intMessage(msgId, subType) |

（另有 give_item=旧 Java 动作保留；面板 add_item/grant_item 均已覆盖）

### 4.2 原已有效（24 个）

set_silver/set_gold/set_sp/add_sp/fill_sp/set_move_speed/set_gravity/teleport/switch_scene/
switch_scene_preset/set_faction/spawn_dummy/send_test_mail/minimal_login/kick/ninjiuzhi_set/
ninjiuzhi_event/ninjiuzhi_reset/ninjiuzhi_status/action_state_fishing/action_state_hsqs05/
action_state_sgmd/action_state_stop/action_native_fishing/worldboss_*(自研)/twilight_*(自研)

### 4.3 第二批待做（逆向已完成、实现待排期）

- **坐骑**：mount_ride（handleMountSummon；mountConfig→坐骑表）
- **卡牌**：equip_card/equip_hair(卡牌 hair-only 校验)/unequip_card/grant_all_cards
  （equipCardLocked+pushCardRecords+pushCardVisual+saveCardProgress）
- **时装**：grant_all_fashion（fashion catalog /api/fashion-catalog）
- **武学**：learn_all_taolu/forget_all_taolu（skills.json/skill_init.ini 落盘+重启生效）、
  learn_all_neigong（progressBookFrame+vitalUpdate）
- **词条**：recast_add_affix(slot+packIDs+affixKind=prop|dire)/recast_clear_affix/
  （recast_slot_polish/skill 洗练=runGMRecast）
- **模板装备**：gm_generate_weapon/armor/bracer（gmTemplateRollDistinct 词条 Roll）
- **副本**：enter_clone/reenter_clone/destroy_clone（cloneEnterConfigGM/cloneReenter/cloneDestroy）
- **声望/出身**：repute_apply(name+mode+value)、origin_activate_all（ensureAllOriginsActivated）
- **经脉**：set_jingmai_tier（gmSetMeridianTier+grantStarterJingMai+recomputeCombat）
- **天气**：set_weather（weatherPresetByKey→updateSceneWeatherRecord，WeatherRec 帧）
- **兵录**：binglu（processBingluGMCommand，参数 bingluArgs）
- **队伍**：team（gmTeamCommand，keyArgs）
- **假人**：spawn_combat_dummy（parseCombatDummySkillNames+pkModeSnapshot）/clear_combat_dummies
- **快捷栏**：save_shortcuts（快捷栏位按键.json）/keybind
- **探针**：popup_gm_panel（custom 30/377 "__gm_popup__"）、fightword_probe（ID24 飘字）
- **场景任务**（成都 791）：scene_city05/scene_task_remove/scene_task_submit_ready/
  authored_task_start/remove/reward/submit_ready/scene_task_reward_makegood
- **伙伴**：bot_patrol_return/bot_support_configure；礼包商人 giftbox_merchant_here
- **御风**：grant/clear wind state（菜单内固定，面板无按钮）

逆向素材：`tmp-analysis/handle_func17_trace.txt`（1884 行全函数 call/string 跟踪）、
`tmp-analysis/gm_helpers/*.c`（32 个 helper 反编译）、`tmp-analysis/menu2_index.html`（面板）。

## 5. 验证

- `mvnw -pl nineyin,gm-web test`：262 通过 / 0 失败（16 skip 为既有）
- 面板实测路径：gm-live 面板对应按钮 → 观察「最近动作」回执与游戏内效果
  （称号/官银/体力在角色面板、气泡在头顶、宝箱点击交互）
