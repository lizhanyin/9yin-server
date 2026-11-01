package com.nineyin.server.db.role;

import java.util.regex.Pattern;

/** 测试辅助：Go 风格 DSN 解析（与 MySqlRoleRepository 同一格式约定）。 */
final class MySqlRoleRepositoryTestHarness {

    static final Pattern DSN = Pattern.compile("^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)(?:\\?(.*))?$");

    private MySqlRoleRepositoryTestHarness() {
    }
}
