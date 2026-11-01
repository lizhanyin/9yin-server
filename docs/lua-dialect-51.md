# Lua 引擎方言对齐客户端（lua55 → lua51 方案,未执行）

**背景**:客户端脚本环境为 Lua 5.1,服务端技能/公式引擎当前是
party.iroiro.luajava 4.1.0 + `lua55`(native Lua 5.5)。方言不一致的代价:
官方 5.1 脚本搬进 `scripts/` 时需人工防 5.3+ 语法混入,且行为差异(整数语义等)要靠人记。

**结论(2026-11-02 评估)**:迁移 = **4 个文件约 12 行纯机械替换,`scripts/` 零改动**。
依据见下文扫描;`lua51:4.1.0` 与 `lua51-platform:4.1.0:natives-desktop`
在 Maven Central 均存在(repo1 目录实查;search.maven.org 索引只到 4.0.2 是滞后),
`Lua51` 类位于 `party.iroiro.luajava.lua51`,与 `Lua55` 同构。

## 改动清单

| 文件 | 改动 |
|---|---|
| `pom.xml`(根,L151/L156) | `lua55`→`lua51`、`lua55-platform`→`lua51-platform`,版本仍 4.1.0;注释同步 |
| `nineyin/pom.xml`(L43/L47) | 同上两处 artifactId |
| `nineyin/.../script/LuaScriptService.java` | 3 处:import `...lua55.Lua55`→`...lua51.Lua51`;`new Lua55()`→`new Lua51()`(L151);日志串 `lua55`→`lua51`(L161) |
| `nineyin/.../script/LuaFormulaParityTest.java` | 3 处字样:类注释、测试方法名 `lua55NativesLoadAndFormulaModuleBinds`、断言消息 |
| `docs/skill-script-framework.md`(L4) | 引擎描述 `lua55 natives`→`lua51 natives`(迁移落地时一并改) |

其余 Java 代码全部走版本无关的 `party.iroiro.luajava.Lua` 接口
(`openLibraries/load/pCall/getGlobal/getField/createTable/push/setField/rawSetI/rawGetI/rawLength/isTable/isNumber/isString/isNil/toNumber/toBoolean/toString/pop/close`),
不受引擎版本影响,一行不用动。

## 脚本层零改动的依据(全量扫描)

Python 正则扫 `scripts/` 全部 **148 个 .lua、5693 行**(代码行,剔除注释),
针对 5.1 之后的语言特性逐项查:

| 特性 | 引入版本 | 命中 |
|---|---|---|
| `goto` | 5.2 | 0 |
| `table.unpack` | 5.2 | 0 |
| `string.rep(s,n,sep)` | 5.2 | 0 |
| `load` 四参(环境) | 5.2 | 0 |
| 位运算 `& \| ~ << >>` | 5.3 | 0 |
| 整除 `//` | 5.3 | 0 |
| `string.pack/unpack` | 5.3 | 0 |
| `math.type` / `utf8.*` | 5.3 | 0 |
| `<close>` / `<const>` | 5.4 | 0 |

仅有的 `~` 字符是 `~=`(5.0 起标准不等号)与注释;`0x` 十六进制字面量 5.1 即有。
`scripts/` 本来就是按客户端 5.1 方言移植的,方向 5.5→5.1 对脚本是纯放宽。

## 语义差异与规避(都已天然无碍)

- **5.1 数字全 double**:Java 侧本就 `push(double)` 写入、`toNumber`+`Math.floor(+1e-9)` 读回
  (`LuaScriptService` setNum/readInt);buff `static_data` 等值域远在 2^53 精度内,
  脚本未用位运算/整数除法 → 无需任何适配。
- **`rawLength`**:5.1 映射 `lua_objlen`,由 luajava 库层吸收,调用方无感。
- **5.3+ 的 `%d` 须整数、`math.floor` 返回整数**等差异:脚本未用 `string.format` 相关路径,
  且这些是 5.1→5.3 的坑,降级方向不存在。

## 验证

```
mvn -pl nineyin test -Dtest=LuaFormulaParityTest
```

该测试同时钉死:natives 在 shade/测试环境可加载 + `formula.lua` 与 Java 兜底
`CombatFormulas` 网格对照逐值一致——引擎换血后公式行为不变的判据。
GM `reload_scripts` 热重载链路无需回归(重建的是 `Lua` 接口引用)。

## 落地收益

服务端与客户端方言完全一致后,**官方 5.1 公式/技能脚本可原样搬进 `scripts/`**,
不再需要"移植时防新语法"的人工检查;后续新增脚本可用客户端同款 lint 环境校验。
