package com.wanpg.flume.sink;


import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class FlumeSink2DB extends AbstractSink implements Configurable {
    private Logger LOG = LoggerFactory.getLogger(FlumeSink2DB.class);
    // 数据库 用户名
    private String user;
    // 数据库 密码
    private String password;

    private String jdbcDriver;
    private String databaseUrl;

    // 连接
    private Connection conn;

    // 多大size提交一次
    private int batchSize;

    // 表的config地址，读取此配置
    private String tableConfigFile;

    // 数据格式 fl-table:tablename:content
    // 数据前缀，带有此前缀的才会认为是可用数据，否则丢弃
    private String linePrefix = "fl-table:";
    private String columnSplit = ",";

    private TableConfig tableConfig;

    private final Map<String, TableInfo> tableInfoMap = new HashMap<>();

    @Override
    public void configure(Context context) {
        jdbcDriver = context.getString("jdbcDriver");
        Preconditions.checkNotNull(jdbcDriver, "jdbcDriver must be set!!");

        databaseUrl = context.getString("databaseUrl");
        Preconditions.checkNotNull(databaseUrl, "databaseUrl must be set!!");

        tableConfigFile = context.getString("tableConfig");
        Preconditions.checkNotNull(tableConfigFile, "tableName must be set!!");
        user = context.getString("user");
        Preconditions.checkNotNull(user, "user must be set!!");
        password = context.getString("password");
        Preconditions.checkNotNull(password, "password must be set!!");
        batchSize = context.getInteger("batchSize", 100);
        Preconditions.checkArgument(batchSize > 0, "batchSize must be a positive number!!");
    }

    @Override
    public synchronized void start() {
        super.start();
        System.out.println("mysql sink start");
        // 创建数据库连接
        try {
            Class.forName(jdbcDriver);
            conn = DriverManager.getConnection(databaseUrl, user, password);
            conn.setAutoCommit(false);
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
            // 这两类错误都会导致程序无法运行
            System.exit(1);
        }
        // 准备每个表的数据
        Yaml yaml = new Yaml();
        try {
            tableConfig = yaml.loadAs(new FileInputStream(tableConfigFile), TableConfig.class);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // 为每个table 创建statement
        try {
            for (TableInfo tableInfo : tableConfig.tableConfig) {
                tableInfoMap.put(tableInfo.tableName, tableInfo);
                // 创建statement
                tableInfo.createStatement(conn);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        // 关闭所有连接
        for (Map.Entry<String, TableInfo> entry : tableInfoMap.entrySet()) {
            PreparedStatement value = entry.getValue().statement;
            if (value != null) {
                try {
                    value.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        System.out.println("mysql sink stop");
    }

    @Override
    public Status process() throws EventDeliveryException {
        System.out.println("mysql sink process");
        Status result = Status.READY;
        Channel channel = getChannel();
        Transaction transaction = channel.getTransaction();

        transaction.begin();
        // 此次process 更新了的 statement
        Map<String, TableInfo> updateTableMap = Maps.newHashMap();
        try {
            for (int i = 0; i < batchSize; i++) {
                Event event = channel.take();
                if (event != null) {//对事件进行处理
                    String content = new String(event.getBody());
                    // 以指定的prefix开头的行，才会认为是有效数据
                    if (content.startsWith(linePrefix)) {
                        String substring = content.substring(linePrefix.length());
                        String tableName = substring.substring(0, substring.indexOf(":"));
                        TableInfo tableInfo = updateTableMap.get(tableName);
                        if (tableInfo == null) {
                            tableInfo = tableInfoMap.get(tableName);
                            // 如果为空，说明配置没有此表，丢弃
                            if (tableInfo == null) {
                                continue;
                            }
                            // 此处意味着此类数据第一次处理
                            tableInfo.statement.clearBatch();
                        }
                        // 拼装数据
                        String infoContent = substring.substring(tableName.length() + 1);
                        String[] split = infoContent.split(columnSplit);
                        boolean addBatchSuccess = false;
                        try {
                            addBatchSuccess = tableInfo.addBatch(split);
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                        if (addBatchSuccess) {
                            if (!updateTableMap.containsValue(tableInfo)) {
                                updateTableMap.put(tableName, tableInfo);
                            }
                        }
                    }
                } else {
                    result = Status.BACKOFF;
                    break;
                }
            }

            if (!updateTableMap.isEmpty()) {
                for (Map.Entry<String, TableInfo> entry : updateTableMap.entrySet()) {
                    PreparedStatement statement = entry.getValue().statement;
                    if (statement != null) {
                        statement.executeBatch();
                    }
                }
                conn.commit();
            }
            transaction.commit();
        } catch (Exception e) {
            try {
                transaction.rollback();
            } catch (Exception e2) {
                LOG.error("Exception in rollback. Rollback might not have been successful.", e2);
            }
            LOG.error("Failed to commit transaction.Transaction rolled back.", e);
            Throwables.propagate(e);
        } finally {
            transaction.close();
        }
        return result;
    }
}
