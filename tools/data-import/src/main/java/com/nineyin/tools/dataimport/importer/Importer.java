package com.nineyin.tools.dataimport.importer;

import java.sql.SQLException;

/** 一个数据导入器：清空目标表并从 data/ JSON 重建。 */
public interface Importer {

    String name();

    String description();

    /** 单导入器单事务：先 DELETE 目标表再批量 INSERT；失败回滚本导入器不影响其他。 */
    void run(ImportContext ctx) throws SQLException;
}
