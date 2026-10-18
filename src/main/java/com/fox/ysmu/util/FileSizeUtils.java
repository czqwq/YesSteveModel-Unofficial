package com.fox.ysmu.util;

import java.text.DecimalFormat;

/**
 * 把字节数格式化成人类可读的字符串；模型管理 GUI 的列表行用它显示条目大小。
 * <p>逐字移植自参考分支 {@code tmp/YesSteveModel-Unofficial-perf-previewUI/src/main/java/com/fox/ysmu/util/
 * FileSizeUtils.java}（其本身来自 LgeacyYSM 的 {@code util/FileSizeUtils.java}，注释里的 StackOverflow 链接
 * 也保留着），唯一变化是数字格式串按 {@code Locale.ROOT} 生成，避免部分语言下出现逗号/点混用导致 GUI 文本
 * 抖动。 </p>
 */
public final class FileSizeUtils {

    private static final String[] UNITS = new String[] { "B", "kB", "MB", "GB", "TB" };

    private FileSizeUtils() {}

    /** 人类可读的字节数（1024 进制，一位小数）；0 或负数显示 {@code "0"}。 */
    public static String size(long size) {
        if (size <= 0) {
            return "0";
        }
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups < 0) {
            digitGroups = 0;
        }
        if (digitGroups >= UNITS.length) {
            digitGroups = UNITS.length - 1;
        }
        return new DecimalFormat("#,##0.#", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT))
            .format(size / Math.pow(1024, digitGroups)) + " " + UNITS[digitGroups];
    }
}
