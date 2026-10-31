package com.nineyin.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua55.Lua55;
import party.iroiro.luajava.value.LuaValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lua 脚本宿主（LuaJava / native Lua 5.5）。
 *
 * <p>承载技能效果钩子（scripts/skill/&lt;skillId&gt;.lua）与伤害公式
 * （scripts/combat/formula.lua），脚本为纯函数：输入只读快照 table，输出指令 table，
 * 不持有玩家状态、不接触 Session/网络层（ADR：docs/script-system-assessment.md）。
 *
 * <p>线程模型：native LuaState 非线程安全，所有调用经本类 synchronized 收敛
 * （出招受 500ms 互斥锁限频，锁竞争可忽略）。热重载 = 整体重建 Lua state
 * （旧模块引用全部随旧 state 失效，避免残留），GM reload_scripts 触发。
 *
 * <p>沙箱：仅开放 base/string/table/math 白名单库（不挂 io/os/debug/package）；
 * LuaJava 默认注入的 {@code java} 互操作全局置 nil（防脚本误用，非对抗性边界）；
 * {@code print} 重定向到 slf4j。脚本运行错误抛 {@link LuaException}，
 * 由调用方（call）统一捕获降级，绝不打断场景 tick。
 */
public final class ScriptHost implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScriptHost.class);

    /** 沙箱白名单标准库（base 含 pcall/pairs/tostring 等；不开 io/os/debug/package）。 */
    private static final String[] SANDBOX_LIBRARIES = {"base", "string", "table", "math"};

    /** lua_pcall 的 LUA_MULTRET。 */
    private static final int MULTRET = -1;

    private Lua state;
    /** 技能模块（skillId → module table 的 calc/on_cast/on_hit 函数容器）。 */
    private final Map<String, LuaValue> skillModules = new LinkedHashMap<>();
    /** 内功模块（buffId → module table 的 on_attack/on_victim/on_parry/on_tick/passive 函数容器）。 */
    private final Map<String, LuaValue> neigongModules = new LinkedHashMap<>();
    /** 伤害公式模块的 calc 函数。 */
    private LuaValue formulaCalc;
    private Path scriptsRoot;
    private boolean available;
    private int lastLoadCount;

    /** 定位脚本根：env NINEYIN_SERVER_ROOT 优先，否则从 cwd 逐级向上找 scripts/；找不到返回 null。 */
    public static Path locateScriptsRoot() {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim(), "scripts");
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("scripts");
            if (Files.isDirectory(candidate.resolve("skill"))
                    || Files.isDirectory(candidate.resolve("combat"))
                    || Files.isDirectory(candidate.resolve("neigong"))) {
                return candidate;
            }
        }
        return null;
    }

    /** 加载脚本根（不存在 = 无脚本模式，服务器照常跑默认管线）。 */
    public synchronized void load(Path root) {
        scriptsRoot = root;
        closeState();
        skillModules.clear();
        neigongModules.clear();
        formulaCalc = null;
        lastLoadCount = 0;
        available = false;
        if (root == null || !Files.isDirectory(root)) {
            log.info("script host disabled: no scripts/ directory");
            return;
        }
        try {
            state = new Lua55();
        } catch (LinkageError | RuntimeException e) {
            // LinkageError：native 库解压/加载失败（shade 合并异常、平台不匹配）
            log.error("script host disabled: lua native init failed: {}", e.toString());
            state = null;
            return;
        }
        for (String lib : SANDBOX_LIBRARIES) {
            try {
                state.openLibrary(lib);
            } catch (LuaException e) {
                log.error("script host disabled: cannot open library {}: {}", lib, e.toString());
                closeState();
                return;
            }
        }
        // LuaJava 默认挂 java 互操作库 → 置 nil（脚本按纯函数契约运行）
        state.set("java", state.fromNull());
        redirectPrint();
        Path combatRoot = root.resolve("combat");
        if (Files.isDirectory(combatRoot)) {
            loadFormula(combatRoot.resolve("formula.lua"));
        }
        Path skillRoot = root.resolve("skill");
        if (Files.isDirectory(skillRoot)) {
            try (Stream<Path> files = Files.list(skillRoot)) {
                files.filter(p -> p.getFileName().toString().endsWith(".lua"))
                        .sorted()
                        .forEach(this::loadSkillModule);
            } catch (IOException e) {
                log.error("script host: list {} failed: {}", skillRoot, e.toString());
            }
        }
        Path neigongRoot = root.resolve("neigong");
        if (Files.isDirectory(neigongRoot)) {
            try (Stream<Path> files = Files.list(neigongRoot)) {
                files.filter(p -> p.getFileName().toString().endsWith(".lua"))
                        .sorted()
                        .forEach(this::loadNeigongModule);
            } catch (IOException e) {
                log.error("script host: list {} failed: {}", neigongRoot, e.toString());
            }
        }
        available = state != null;
        lastLoadCount = skillModules.size() + neigongModules.size() + (formulaCalc != null ? 1 : 0);
        log.info("script host loaded: root={} skills={} neigong={} formula={}",
                root, skillModules.size(), neigongModules.size(), formulaCalc != null);
    }

    /** 热重载（重建 Lua state + 重读全部脚本）。 */
    public synchronized void reload() {
        load(scriptsRoot);
    }

    /** 技能脚本模块（无该技能脚本返回 null）。 */
    public synchronized LuaValue skillModule(String skillId) {
        return available ? skillModules.get(skillId) : null;
    }

    /** 内功脚本模块（无该内功脚本返回 null；键 = buffId，如 buf_ng_wd_001）。 */
    public synchronized LuaValue neigongModule(String buffId) {
        return available ? neigongModules.get(buffId) : null;
    }

    /** 伤害公式 calc 函数（无 formula.lua 返回 null）。 */
    public synchronized LuaValue formulaFunction() {
        return available ? formulaCalc : null;
    }

    public synchronized boolean available() {
        return available;
    }

    public synchronized int loadedCount() {
        return available ? lastLoadCount : 0;
    }

    public synchronized boolean hasFormula() {
        return available && formulaCalc != null;
    }

    /**
     * pcall 语义的函数调用（单 table 参数）：参数走 {@link Lua#push(java.util.Map)}
     * 深度转换（嵌套 Map/List 递归转 Lua table；LuaValue.call(Object...) 的参数
     * 转换不递归，嵌套容器会变成 Java 代理导致脚本无法索引）。
     * 返回值 table 由 {@link #deepValue} 深度转回 Map/List（LuaJava 的 toObject
     * 只做浅层转换，嵌套 table 不转）；无返回/脚本错误 → WARN 并返回 null，
     * 由调用方回退 Java 默认管线。
     */
    public synchronized Object callMap(LuaValue fn, Map<String, Object> arg) {
        if (!available || fn == null) {
            return null;
        }
        int base = state.getTop();
        try {
            fn.push(state);
            state.push(arg);
            state.pCall(1, MULTRET);
            int results = state.getTop() - base;
            if (results < 1) {
                state.pop(results);
                return null;
            }
            Object top = deepValue(state, -1);
            state.pop(results);
            return top;
        } catch (LuaException e) {
            if (state.getTop() > base) {
                state.setTop(base);   // 出错时栈可能残留 error 对象，复位保护
            }
            log.warn("lua call failed ({}): {}", e.type, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("lua call failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 栈值 → Java 深度转换：nil→null、boolean/integer/number/string 原样、
     * table 经 lua_next 全遍历（键全为连续 "1".."n" 的数组形态转 List，否则转 Map）。
     * 递归处理嵌套 table；函数/线程等其余类型返回 null。
     */
    private static Object deepValue(Lua L, int idx) {
        return switch (L.type(idx)) {
            case NIL, NONE -> null;
            case BOOLEAN -> L.toBoolean(idx);
            case NUMBER -> L.isInteger(idx)
                    ? (Object) Long.valueOf(L.toInteger(idx))
                    : (Object) Double.valueOf(L.toNumber(idx));
            case STRING -> L.toString(idx);
            case TABLE -> {
                int abs = idx >= 0 ? idx : L.getTop() + idx + 1;
                Map<String, Object> map = new LinkedHashMap<>();
                L.pushNil();
                while (L.next(abs) != 0) {
                    // 栈：…, key, value（key 由 next 消耗后压回）。
                    // key 必须先复制再转字符串：lua_tolstring 会就地替换栈值
                    // （数字键→字符串），下一轮 lua_next 即 'invalid key' panic。
                    L.pushValue(-2);
                    String key = L.toString(-1);
                    L.pop(1);
                    map.put(key, deepValue(L, -1));
                    L.pop(1);   // 弹 value 留 key，供下一轮 next
                }
                if (map.isEmpty()) {
                    yield Map.of();
                }
                boolean arrayLike = true;
                for (int i = 1; i <= map.size(); i++) {
                    if (!map.containsKey(Integer.toString(i))) {
                        arrayLike = false;
                        break;
                    }
                }
                if (arrayLike) {
                    List<Object> list = new ArrayList<>(map.size());
                    for (int i = 1; i <= map.size(); i++) {
                        list.add(map.get(Integer.toString(i)));
                    }
                    yield list;
                }
                yield map;
            }
            default -> null;
        };
    }

    /** 诊断/测试用：在沙箱内 eval 一段 Lua（返回 nil 语义见 LuaValue.type()==NIL）。 */
    public synchronized LuaValue[] eval(String code) {
        if (!available) {
            return null;
        }
        try {
            return state.eval(code);
        } catch (LuaException e) {
            log.warn("lua eval failed ({}): {}", e.type, e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized void close() {
        closeState();
        skillModules.clear();
        neigongModules.clear();
        formulaCalc = null;
        available = false;
    }

    private void closeState() {
        if (state != null) {
            try {
                state.close();
            } catch (RuntimeException e) {
                log.debug("lua state close: {}", e.toString());
            }
            state = null;
        }
    }

    private void loadFormula(Path file) {
        LuaValue module = evalModule(file);
        if (module == null) {
            return;
        }
        LuaValue calc = module.get("calc");
        if (calc == null || calc.type() != Lua.LuaType.FUNCTION) {
            log.error("formula.lua has no calc function; java fallback stays active");
            return;
        }
        formulaCalc = calc;
    }

    private void loadSkillModule(Path file) {
        String name = file.getFileName().toString();
        String skillId = name.substring(0, name.length() - ".lua".length());
        LuaValue module = evalModule(file);
        if (module == null) {
            return;
        }
        if (module.type() != Lua.LuaType.TABLE) {
            log.error("skill script {} must return a module table; skipped", skillId);
            return;
        }
        skillModules.put(skillId, module);
    }

    /** 内功脚本装载（约定同技能脚本：local M = {} … return M；文件名 = buffId）。 */
    private void loadNeigongModule(Path file) {
        String name = file.getFileName().toString();
        String buffId = name.substring(0, name.length() - ".lua".length());
        LuaValue module = evalModule(file);
        if (module == null) {
            return;
        }
        if (module.type() != Lua.LuaType.TABLE) {
            log.error("neigong script {} must return a module table; skipped", buffId);
            return;
        }
        neigongModules.put(buffId, module);
    }

    /** eval 整个文件（模块约定：local M = {} … return M）；失败记 ERROR 并返回 null。 */
    private LuaValue evalModule(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            log.error("read script {} failed: {}", file, e.toString());
            return null;
        }
        try {
            LuaValue[] values = state.eval(source);
            return values != null && values.length > 0 ? values[0] : null;
        } catch (LuaException e) {
            log.error("load script {} failed ({}): {}", file, e.type, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.error("load script {} failed: {}", file, e.toString());
            return null;
        }
    }

    /** print(...) 重定向 slf4j（native stdout 会绕过 logback 编码）。 */
    private void redirectPrint() {
        state.push((JFunction) lua -> {
            StringBuilder sb = new StringBuilder();
            int top = lua.getTop();
            for (int i = 1; i <= top; i++) {
                if (i > 1) {
                    sb.append("  ");
                }
                sb.append(lua.toString(i));
            }
            log.info("[lua] {}", sb);
            return 0;
        });
        state.setGlobal("print");
    }
}
