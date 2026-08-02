package com.silas.iris.toolgateway.logs.utils;

import com.silas.iris.toolgateway.logs.constant.LogsQueryConstants;
import com.silas.iris.toolgateway.logs.model.vo.LokiQueryRangeVO;
import com.silas.iris.toolgateway.logs.model.vo.QueryLogsVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将 Loki 原始日志行归一化分组，并汇总 traceId。
 */
public final class LogPatternAggregator {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![\\d.])");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])");

    private LogPatternAggregator() {
    }

    public static AggregationResult aggregate(LokiQueryRangeVO raw) {
        List<String> lines = flattenLines(raw);
        int processedLineCount = Math.min(lines.size(), LogsQueryConstants.MAX_RAW_LINES);
        boolean rawLinesTruncated = isRawLinesTruncated(raw, lines.size(), processedLineCount);

        Map<String, MutablePatternGroup> groups = new LinkedHashMap<>();
        Set<String> distinctTraceIds = new LinkedHashSet<>();
        for (int i = 0; i < processedLineCount; i++) {
            String line = lines.get(i);
            String traceId = TraceIdExtractor.extract(line).orElse(null);
            if (traceId != null) {
                distinctTraceIds.add(traceId);
            }

            String normalized = normalize(line, traceId);
            MutablePatternGroup group = groups.computeIfAbsent(normalized,
                    ignored -> new MutablePatternGroup(line));
            group.count++;
            if (traceId != null) {
                group.traceIds.add(traceId);
            }
        }

        List<QueryLogsVO.PatternGroup> patterns = groups.entrySet().stream()
                .sorted(Map.Entry.<String, MutablePatternGroup>comparingByValue(
                                Comparator.comparingLong(MutablePatternGroup::count).reversed())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(LogsQueryConstants.MAX_PATTERN_GROUPS)
                .map(entry -> entry.getValue().toVO(entry.getKey()))
                .toList();

        QueryLogsVO data = QueryLogsVO.builder()
                .totalLines(processedLineCount)
                .patterns(patterns)
                .distinctTraceIds(new ArrayList<>(distinctTraceIds))
                .build();
        return new AggregationResult(data,
                rawLinesTruncated || groups.size() > LogsQueryConstants.MAX_PATTERN_GROUPS);
    }

    private static List<String> flattenLines(LokiQueryRangeVO raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null || raw.getData() == null || raw.getData().getResult() == null) {
            return lines;
        }
        for (LokiQueryRangeVO.StreamResult stream : raw.getData().getResult()) {
            if (stream == null || stream.getValues() == null) {
                continue;
            }
            for (List<String> value : stream.getValues()) {
                if (value != null && value.size() >= 2 && value.get(1) != null) {
                    lines.add(value.get(1));
                    if (lines.size() > LogsQueryConstants.MAX_RAW_LINES) {
                        return lines;
                    }
                }
            }
        }
        return lines;
    }

    private static boolean isRawLinesTruncated(LokiQueryRangeVO raw, int returnedLines, int processedLines) {
        if (returnedLines > LogsQueryConstants.MAX_RAW_LINES) {
            return true;
        }
        Long totalPostFilterLines = null;
        if (raw != null && raw.getData() != null && raw.getData().getStats() != null
                && raw.getData().getStats().getSummary() != null) {
            totalPostFilterLines = raw.getData().getStats().getSummary().getTotalPostFilterLines();
        }
        return totalPostFilterLines != null
                ? totalPostFilterLines > processedLines
                : returnedLines >= LogsQueryConstants.MAX_RAW_LINES;
    }

    private static String normalize(String line, String traceId) {
        String normalized = line;
        if (traceId != null) {
            normalized = normalized.replace(traceId, "<traceId>");
        }
        normalized = UUID_PATTERN.matcher(normalized).replaceAll("<uuid>");
        normalized = IPV4_PATTERN.matcher(normalized).replaceAll("<ip>");
        return NUMBER_PATTERN.matcher(normalized).replaceAll("N");
    }

    /**
     * 聚合数据及其裁剪状态，供 controller 构造响应 meta。
     */
    public record AggregationResult(QueryLogsVO data, boolean truncated) {
    }

    private static final class MutablePatternGroup {
        private long count;
        private final String sampleLine;
        private final Set<String> traceIds = new LinkedHashSet<>();

        private MutablePatternGroup(String sampleLine) {
            this.sampleLine = sampleLine;
        }

        private long count() {
            return count;
        }

        private QueryLogsVO.PatternGroup toVO(String pattern) {
            return QueryLogsVO.PatternGroup.builder()
                    .pattern(pattern)
                    .count(count)
                    .sampleLine(sampleLine)
                    .traceIds(new ArrayList<>(traceIds))
                    .build();
        }
    }
}
