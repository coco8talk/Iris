package com.iris.gateway.logs;

import com.iris.gateway.logs.vo.LogPatternVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 日志模板聚合：确定性归一后按模板分组计数(T15)。
 *
 * <p>归一规则（按序）：UUID → {@code <UUID>}、32/16 位 hex → {@code <HEX>}、
 * 连续数字 → {@code <N>}。纯函数、零依赖，不引入 Drain 等日志模板库(KISS)。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
public final class PatternAggregator {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{16})\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 每个模板保留的样本 traceId 上限 */
    private static final int MAX_SAMPLES = 3;

    private PatternAggregator() {
    }

    /**
     * 归一化单条消息为模板串。
     *
     * @param msg 原始消息
     * @return 模板串
     */
    public static String normalize(String msg) {
        if (msg == null) {
            return "";
        }
        String s = UUID.matcher(msg).replaceAll("<UUID>");
        s = HEX.matcher(s).replaceAll("<HEX>");
        s = DIGITS.matcher(s).replaceAll("<N>");
        return s;
    }

    /**
     * 聚合日志行为模板项，按出现次数降序。
     *
     * @param lines 解析后的日志行
     * @return 模板聚合列表
     */
    public static List<LogPatternVO> aggregate(List<LogLine> lines) {
        Map<String, Acc> byPattern = new LinkedHashMap<>();
        for (LogLine line : lines) {
            String pattern = normalize(line.msg());
            Acc acc = byPattern.computeIfAbsent(pattern, k -> new Acc());
            acc.add(line);
        }
        return byPattern.entrySet().stream()
                .map(e -> e.getValue().toVo(e.getKey()))
                .sorted(Comparator.comparingLong(LogPatternVO::count).reversed())
                .toList();
    }

    /** 单模板累加器：计数、首末时间、样本 traceId */
    private static final class Acc {
        private long count;
        private String firstSeen;
        private String lastSeen;
        private final List<String> sampleTraceIds = new ArrayList<>();

        void add(LogLine line) {
            count++;
            String ts = line.ts();
            if (ts != null) {
                if (firstSeen == null || ts.compareTo(firstSeen) < 0) {
                    firstSeen = ts;
                }
                if (lastSeen == null || ts.compareTo(lastSeen) > 0) {
                    lastSeen = ts;
                }
            }
            String traceId = line.traceId();
            if (traceId != null && sampleTraceIds.size() < MAX_SAMPLES && !sampleTraceIds.contains(traceId)) {
                sampleTraceIds.add(traceId);
            }
        }

        LogPatternVO toVo(String pattern) {
            return new LogPatternVO(pattern, count, firstSeen, lastSeen, List.copyOf(sampleTraceIds));
        }
    }
}
