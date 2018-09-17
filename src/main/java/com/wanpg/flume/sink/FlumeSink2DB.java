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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * flume sink to db
 * <p>
 * 2018/09/15 支持从flume 读取数据写入各种支持jdbc的数据库<br>
 * 2018/09/17 支持flume 每隔指定时间可以重新读取配置（本地或网络）<br>
 *
 * @author wangjinpeng
 */
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

    // 数据格式 fl-table:tablename,content
    // 数据前缀，带有此前缀的才会认为是可用数据，否则丢弃
    private String linePrefix = "fl-table:";
    private String columnSplit;

    private long nextConfigCheckTime;

    private final Map<String, TableInfo> tableInfoMap = new ConcurrentHashMap<>();

    private Calendar instance;

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

        columnSplit = context.getString("columnSplit", ",");
        Preconditions.checkNotNull(columnSplit, "password must be set!!");

        batchSize = context.getInteger("batchSize", 100);
        Preconditions.checkArgument(batchSize > 0, "batchSize must be a positive number!!");
    }

    @Override
    public synchronized void start() {
        super.start();
        log("mysql sink start");
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
        loadTableConfig();
        nextConfigCheckTime = calNextConfigCheckTime();
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
        log("mysql sink stop");
    }

    @Override
    public Status process() throws EventDeliveryException {
        // 先进行一次表配置的加载
        check2LoadConfig();
        log("mysql sink process");
        if (tableInfoMap.isEmpty()) {
            return Status.READY;
        }
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
                    String[] array = content.split(columnSplit);
                    log("读取当前信息:" + content);
                    // 长度要超过1个，才计入当前jar的处理范围
                    if (array.length <= 1) {
                        continue;
                    }
                    String firstData = array[0];
                    // 取出第1个数据，必须符合表的结构获取表的信息
                    if (!firstData.startsWith(linePrefix)) {
                        continue;
                    }
                    String tableName = firstData.substring(firstData.indexOf(":") + 1);
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
                    int copyLength = array.length - 1;
                    // 复制后面的数据
                    String[] columns = new String[copyLength];
                    System.arraycopy(array, 1, columns, 0, copyLength);
                    log("columns内容:" + Arrays.toString(columns));
                    boolean addBatchSuccess = false;
                    try {
                        addBatchSuccess = tableInfo.addBatch(columns);
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                    if (addBatchSuccess) {
                        if (!updateTableMap.containsValue(tableInfo)) {
                            updateTableMap.put(tableName, tableInfo);
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
            e.printStackTrace();
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


    // 定时每天执行
    private synchronized void check2LoadConfig() {
        if (System.currentTimeMillis() >= nextConfigCheckTime) {
            nextConfigCheckTime = calNextConfigCheckTime();
            loadTableConfig();
        }
    }


    /**
     * 检查并加载表信息
     * 到制定地址读取表的配置
     */
    private synchronized void loadTableConfig() {
        TableConfig tableConfig = null;
        // 准备每个表的数据
        Yaml yaml = new Yaml();
        log("表配置地址:" + tableConfigFile);
        URI uri = URI.create(tableConfigFile);
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            URLConnection urlConnection;
            InputStream inputStream;
            try {
                urlConnection = uri.toURL().openConnection();
                inputStream = urlConnection.getInputStream();
                tableConfig = yaml.loadAs(inputStream, TableConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                tableConfig = yaml.loadAs(new FileInputStream(tableConfigFile), TableConfig.class);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        // 清空表，重新读取配置
        tableInfoMap.clear();
        if (tableConfig != null && tableConfig.tableConfig != null) {
            log("读取的表信息配置是：" + tableConfig.tableConfig.size());
            // 为每个table 创建statement
            try {
                for (TableInfo tableInfo : tableConfig.tableConfig) {
                    tableInfoMap.put(tableInfo.tableName, tableInfo);
                    // 创建statement
                    tableInfo.createStatement(conn);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            log("未读取到表的信息");
        }
    }

    private void log(String info) {
        System.out.println(info);
    }

    /**
     * 获取config check的间隔时间，下一个小时到现在的时间
     *
     * @return
     */
    private long calNextConfigCheckTime() {
        if (instance == null) {
            instance = GregorianCalendar.getInstance(Locale.CHINA);
        }
        // 设置当前时间
        instance.setTime(new Date());
        // 设置分和秒归0
        instance.set(Calendar.MINUTE, 0);
        instance.set(Calendar.SECOND, 0);
        // 添加一小时
        return instance.getTimeInMillis() + 60 * 60 + 1000;
    }
}
