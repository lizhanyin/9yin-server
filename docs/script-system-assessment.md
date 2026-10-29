# 脚本系统接入评估（Architecture Decision Record）

> 评估日期：2026-10-29。
> 问题：服务端是否需要接入脚本系统（LuaJ / GraalJS / Groovy 等嵌入式脚本引擎）承载游戏玩法逻辑。
> 结论先行：**现阶段不接入，延后到"玩法内容生产期"（任务/剧情、活动/福利批量落地阶段）再评估。届时优先扩数据驱动 DSL，其次才是嵌入式脚本。**

---

## 评估背景

- 本项目为研究型私服复刻（Java 21 + Summer 框架），当前总体完成度 ≈10%（见
  [roadmap-workitems.md](roadmap-workitems.md)）：单机闭环可跑，玩家互相不可见、
  无物品实体、任务系统为零——三大支柱（物品/任务/成长）均未定型。
- **原服务器 Lua 已全部丢失**（`bossclone003xxx.lua`、技能特效链
  `skill\clone\clone003\*.lua`、掉落组合表 `comb_PassDrop_*` 等，见
  [clone003-boss-mechanics.md](clone003-boss-mechanics.md)）。现有 BOSS 出招循环、
  召唤、掉落均为"按攻略近似"硬编码在 `AiEventEngine`。
- 现有架构已走数据驱动路线：`AiEventEngine` 四表 ini 事件机、`BuffCatalog`、
  `WorldBossManager` 时间档、`QinggongCatalog`、`ShopRepository` 均由 INI/JSON 驱动。

## 不接入的理由

1. **当前瓶颈不在逻辑表达方式。** roadmap"建议推进顺序"前 10 项（玩家可见、
   物品实体、商店闭环、仇恨 AI、技能倍率/冷却、buff 数值、任务骨架、持久化、
   聊天、副本波次）全是协议对齐与系统骨架工程，Java + 数据表完全胜任；在
   核心 API 未定型时接脚本层，等于为不稳定接口做绑定，后续随系统重构反复返工。
2. **没有存量脚本资产可复用。** 接脚本引擎的最大收益场景是"原服脚本还在、
   直接加载跑"，本项目不成立。近似逻辑无论写在 Java 还是 Lua 都要从零重建；
   写在 Java 侧有类型检查与测试锚定（如 `CombatFormulasTest` 16 项），写在
   Lua 里反而丢失这些保障。
3. **脚本系统的核心价值当前用不上。** 它解决"运营期高频改玩法、不停服热更、
   策划独立产出内容"。研究型单机服没有线上玩家、没有停服成本、单人开发重启
   即可。原版用 Lua 是团队 + 长线运营的约束，架构决策应基于自身约束而非复刻
   原版形态。
4. **数据驱动已覆盖"配置变化"场景。** 真正丢不回来的"出招手感"属于信息缺失，
   脚本系统不产生信息。此外接引擎还有实际成本：依赖体积、沙箱边界、脚本异常
   隔离（脚本抛错不能打断场景 tick）、脚本状态与 ECS / MySQL+JSON 双写存档的
   一致性——对单人研究项目是纯负担。

## 重新评估的触发条件

进入 roadmap 第 4 类（任务/剧情）与第 10 类（活动/福利）批量生产期，且出现
以下信号时重开评估：

- 同类玩法在 Java 里类爆炸（每个任务/活动一个类，流程高度相似但参数与编排各异）；
- 出现真实的"改逻辑不重启"需求（封测/多人在场，停服成本变高）。

## 届时的方案优先级

1. **首选：扩数据驱动 DSL**——沿用 `AiEventEngine` 的 OpEvent/条件表思路，补齐
   Func(8)/Func(22) 等枚举语义，把"流程编排"表达成表。零依赖、可测试、与现有
   架构同构，热更问题可用"表文件重载"解决大半。
2. **备选：嵌入式脚本（LuaJ 或 GraalJS）**——若确需图灵完备，限定脚本为
   **纯函数钩子**（输入世界状态快照，输出指令列表），不让脚本持有玩家状态或
   直接访问 Session/ECS 内部，避免污染存档一致性；按钩子点设计（onKill、
   onEnterScene、onTimer 等），脚本异常按条目隔离并记录日志。

## 关联文档

- [roadmap-workitems.md](roadmap-workitems.md) —— 完成度与推进顺序
- [clone003-boss-mechanics.md](clone003-boss-mechanics.md) —— 原服 Lua 丢失范围与近似重建
- [transport-protocol-and-ecs.md](transport-protocol-and-ecs.md) —— 现有架构
