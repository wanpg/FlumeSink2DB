# FlumeSink2DB
利用 Flume Sink 使用一个jar包写入多张表

#### 说明
1. 配置`FlumeSink2DB.conf`到flume的conf目录，配置里面FlumeSink2DB的相应字段，名字可以改
1. 调用下面的指令启动flume
    ```bash
     flume-ng  agent -conf-file ../conf/FlumeSink2DB.conf  -name agent1  -property flume.root.logger=INFO,console
    ```
1. MySqlSink在启动，会到配置agent1.sinks.mysqlSink.tableConfig指向的文件读取配置，可以改为网络访问

   > 详细见同级目录的`db_table_config.yml`

2. 加载配置后，为每个表生成一个`PreparedStatement`

3. 在process中，根据每一行传入的数据进行判断使用哪个表的`PreparedStatement`

   > 由于来的数据是一行行的，并不知道属于哪个表。希望在记日志时对每一行日志加前缀
   >
   > `fl-table:tablename:content`

4. 最后一起提交，完成数据插入

#### 目前还未完成的事情
1. 目前只写了 数据库类型是 字符 类型的。需要完善 int、long、boolean等的类型处理
2. 代码的严谨性需要在测试中完善

#### 需要引用的jar包

```
- 相应的jdbc，demo用的mariaDB
	下载地址：https://downloads.mariadb.com/Connectors/java/connector-java-2.3.0/mariadb-java-client-2.3.0.jar
- yaml解析库
	下载地址：https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/1.23/snakeyaml-1.23.jar
```

#### 相关文档
- [Flume-ng在windows环境搭建](https://blog.csdn.net/antgan/article/details/52087926)
- [Flume自定义sink写入mysql](https://blog.csdn.net/u012373815/article/details/54098581)
- [Windows Tail命令工具](https://www.jianshu.com/p/743964656bb4)