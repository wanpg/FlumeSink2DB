package com.wanpg.flume.sink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public class TableInfo {

    public String tableName;
    public List<Column> columns;

    public PreparedStatement statement;

    public void createStatement(Connection conn) throws SQLException {
        StringBuilder builder =
                new StringBuilder("insert into ")
                        .append("`")
                        .append(tableName)
                        .append("`")
                        .append(" (");
        boolean isFirst = true;
        for (Column column : columns) {
            if (isFirst) {
                isFirst = false;
            } else {
                builder.append(", ");
            }
            builder.append(column.name);
        }
        builder.append(") values (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        builder.append(")");
        statement = conn.prepareStatement(builder.toString());
    }

    public boolean addBatch(String[] dataArray) throws SQLException {
        if (dataArray == null) {
            return false;
        }
        if (dataArray.length != columns.size()) {
            return false;
        }
        for (int i = 0; i < dataArray.length; i++) {
            String data = dataArray[i];
            // TODO: 2018/9/10 此处需要处理不同类型设置参数
            statement.setString(i + 1, data);
        }
        statement.addBatch();
        return true;
    }
}
