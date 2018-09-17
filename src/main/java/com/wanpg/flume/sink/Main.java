package com.wanpg.flume.sink;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class Main {

    public static void main(String[] args) {
        new Thread(() -> {
            try {
                FileWriter writer1 = new FileWriter("/Users/wangjinpeng/Desktop/test_flume.log");
                BufferedWriter writer = new BufferedWriter(writer1);
                for (int i = 0; i < 500; i++) {
                    try {
                        writer.newLine();
                        writer.write(String.format("fl-table:mysqltest,a%d,%d", i, System.currentTimeMillis()));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                writer1.flush();
                writer1.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

    }

}
