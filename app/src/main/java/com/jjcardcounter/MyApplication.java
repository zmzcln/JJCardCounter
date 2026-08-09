package com.jjcardcounter;

import android.app.Application;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 全局 Application：
 * 1) 捕获所有未处理异常，写入 jj_log.txt，避免"闪退看不见原因"。
 * 2) 提供 log()/readLog()/clearLog() 给服务与界面共用。
 */
public class MyApplication extends Application {
    public static final String TAG = "JJCardCounter";
    private static File logFile;
    private static final int MAX_LINES = 400;

    @Override
    public void onCreate() {
        super.onCreate();
        logFile = new File(getFilesDir(), "jj_log.txt");

        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log("==== UNCAUGHT @" + t.getName() + " ====\n" + Log.getStackTraceString(e));
            if (def != null) {
                def.uncaughtException(t, e);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(1);
            }
        });
    }

    public static synchronized void log(String s) {
        if (logFile == null) return;
        try (FileWriter fw = new FileWriter(logFile, true)) {
            fw.append(s).append("\n");
        } catch (IOException ignored) {
        }
        // 防止日志无限增长
        try {
            File tmp = new File(logFile.getParentFile(), "jj_log_tmp.txt");
            BufferedReader br = new BufferedReader(new FileReader(logFile));
            java.util.LinkedList<String> lines = new java.util.LinkedList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
            while (lines.size() > MAX_LINES) lines.removeFirst();
            FileWriter fw = new FileWriter(tmp, false);
            for (String l : lines) fw.append(l).append("\n");
            fw.close();
            if (logFile.delete()) tmp.renameTo(logFile);
        } catch (IOException ignored) {
        }
    }

    public static synchronized String readLog() {
        if (logFile == null || !logFile.exists()) return "(无日志)";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        } catch (IOException e) {
            return "(读取失败)";
        }
        return sb.length() == 0 ? "(空日志)" : sb.toString();
    }

    public static synchronized void clearLog() {
        if (logFile != null) {
            try (FileWriter fw = new FileWriter(logFile, false)) {
                fw.write("");
            } catch (IOException ignored) {
            }
        }
    }
}
