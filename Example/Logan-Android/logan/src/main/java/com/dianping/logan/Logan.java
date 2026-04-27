/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dianping.logan;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Logan {

    private static OnLoganProtocolStatus sLoganProtocolStatus;
    private static LoganControlCenter sLoganControlCenter;
    static boolean sDebug = false;

    /**
     * @brief Logan初始化
     */
    public static void init(LoganConfig loganConfig) {
        sLoganControlCenter = LoganControlCenter.instance(loganConfig);
    }

    /**
     * @param log  表示日志内容
     * @param type 表示日志类型
     * @brief Logan写入日志
     */
    public static void w(String log, int type) {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        sLoganControlCenter.write(log, type);
    }

    /**
     * @brief 立即写入日志文件
     */
    public static void f() {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        sLoganControlCenter.flush();
    }

    /**
     * @param dates    日期数组，格式：“2018-07-27”
     * @param runnable 发送操作
     * @brief 发送日志
     */
    public static void s(String[] dates, SendLogRunnable runnable) {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        sLoganControlCenter.send(dates, runnable);
    }

    /**
     * @param url             接受日志的服务器完整url.
     * @param date            日志日期 格式："2018-11-21".
     * @param appId           当前应用的唯一标识,在多App时区分日志来源App.
     * @param unionId         当前用户的唯一标识,用来区分日志来源用户.
     * @param deviceId        设备id.
     * @param buildVersion    上报源App的build号.
     * @param appVersion      上报源的App版本.
     * @param sendLogCallback 上报结果回调（子线程调用）.
     */
    public static void s(String url, String date, String appId, String unionId, String deviceId,
                         String buildVersion, String appVersion, SendLogCallback sendLogCallback) {
        final Map<String, String> headers = new HashMap<>();
        headers.put("fileDate", date);
        headers.put("appId", appId);
        headers.put("unionId", unionId);
        headers.put("deviceId", deviceId);
        headers.put("buildVersion", buildVersion);
        headers.put("appVersion", appVersion);
        headers.put("platform", "1");
        s(url, date, headers, sendLogCallback);
    }

    /**
     * @param url             接受日志的服务器完整url.
     * @param date            日志日期 格式："2018-11-21".
     * @param headers         请求头信息.
     * @param sendLogCallback 上报结果回调（子线程调用）.
     */
    public static void s(String url, String date, Map<String, String> headers, SendLogCallback sendLogCallback) {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        final SendLogDefaultRunnable sendLogRunnable = new SendLogDefaultRunnable();
        sendLogRunnable.setUrl(url);
        sendLogRunnable.setSendLogCallback(sendLogCallback);
        sendLogRunnable.setRequestHeader(headers);
        sLoganControlCenter.send(new String[]{date}, sendLogRunnable);
    }

    /**
     * @param date     "yyyy-MM-dd" 格式日期。
     * @param types    要上报的日志类型数组。元素为 null 表示旧版未拆分的 {date}
     *                 文件；非空整数表示对应的 {date}_{type} 文件。
     *                 传入 null 或空数组等价于"上传该日期下的全部文件"，
     *                 与 {@link #s(String[], SendLogRunnable)} 行为一致。
     * @param runnable 用户实现的上报逻辑。每匹配一个文件会回调一次 sendLog(File)，
     *                 调用方必须在每次回调结束后调用 {@link SendLogRunnable#finish()}。
     */
    public static void s(String date, Integer[] types, SendLogRunnable runnable) {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        sLoganControlCenter.send(date, types, runnable);
    }

    /**
     * @brief 返回所有日志文件信息
     */
    public static Map<String, Long> getAllFilesInfo() {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        File dir = sLoganControlCenter.getDir();
        if (!dir.exists()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        Map<String, Long> allFilesInfo = new HashMap<>();
        for (File file : files) {
            FileNames.Parsed p = FileNames.parse(file.getName());
            if (p == null || p.isCopy) {
                continue;
            }
            String dateStr = Util.getDateStr(p.dateMillis);
            Long previous = allFilesInfo.get(dateStr);
            allFilesInfo.put(dateStr, (previous == null ? 0L : previous) + file.length());
        }
        return allFilesInfo;
    }

    /**
     * @brief Logan Debug开关
     */
    public static void setDebug(boolean debug) {
        Logan.sDebug = debug;
    }

    static void onListenerLogWriteStatus(String name, int status) {
        if (sLoganProtocolStatus != null) {
            sLoganProtocolStatus.loganProtocolStatus(name, status);
        }
    }

    public static void setOnLoganProtocolStatus(OnLoganProtocolStatus listener) {
        sLoganProtocolStatus = listener;
    }
}
