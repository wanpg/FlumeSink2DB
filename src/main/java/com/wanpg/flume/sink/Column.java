package com.wanpg.flume.sink;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public class Column {
    public String name;
    /**
     * 类型， string, int, long, boolean 等用的时候扩展
     */
    public String type;

    // TODO: 2018/9/14 考虑到data为空或者set出错的情况
    public void setValue(PreparedStatement statement, String data, int index) throws SQLException {
        switch (type) {
            case "string":
                statement.setString(index, data);
                break;
            case "short":
                statement.setShort(index, Short.valueOf(data));
                break;
            case "int":
                statement.setInt(index, Integer.valueOf(data));
                break;
            case "long":
                statement.setLong(index, Long.valueOf(data));
                break;
            case "float":
                statement.setFloat(index, Float.valueOf(data));
                break;
            case "double":
                statement.setDouble(index, Double.valueOf(data));
                break;
            case "byte":
                statement.setByte(index, Byte.valueOf(data));
                break;
            case "boolean":
                statement.setBoolean(index, Boolean.valueOf(data));
                break;
        }
    }
}
