package com.silas.iris.toolgateway.trace.utils;

import com.silas.iris.toolgateway.trace.model.vo.SpanRaw;
import com.silas.iris.toolgateway.trace.model.vo.TraceTreeVO;
import com.silas.iris.toolgateway.trace.model.vo.TraceTreeVO.SpanNode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Tempo 返回的扁平 span 列表拼成树，并按请求深度裁剪。
 */
public final class TraceTreeUtil {

    private TraceTreeUtil() {
    }

    public static TraceTreeVO buildAndPrune(String traceId, List<SpanRaw> rawSpans, int maxDepth) {
        if (rawSpans == null || rawSpans.isEmpty()) {
            throw new IllegalStateException("trace 未包含 span: " + traceId);
        }

        Map<String, SpanRaw> spansById = new HashMap<>();
        for (SpanRaw span : rawSpans) {
            spansById.put(span.getSpanId(), span);
        }

        Map<String, List<SpanRaw>> childrenByParent = rawSpans.stream()
                .filter(span -> span.getParentId() != null && spansById.containsKey(span.getParentId()))
                .collect(java.util.stream.Collectors.groupingBy(SpanRaw::getParentId));
        childrenByParent.values().forEach(children -> children.sort(
                Comparator.comparingLong(SpanRaw::getStartTimeUnixNano)));

        Comparator<SpanRaw> byStartTime = Comparator.comparingLong(SpanRaw::getStartTimeUnixNano);
        SpanRaw rootRaw = rawSpans.stream()
                .filter(span -> span.getParentId() == null)
                .min(byStartTime)
                .orElseGet(() -> rawSpans.stream().min(byStartTime).orElseThrow());

        SpanNode root = pruneRecursive(rootRaw, childrenByParent, 1, maxDepth);
        return TraceTreeVO.builder()
                .traceId(traceId)
                .totalSpanCount(rawSpans.size())
                .appliedMaxDepth(maxDepth)
                .root(root)
                .build();
    }

    private static SpanNode pruneRecursive(SpanRaw current,
                                           Map<String, List<SpanRaw>> childrenByParent,
                                           int depth,
                                           int maxDepth) {
        List<SpanRaw> childRaws = childrenByParent.getOrDefault(current.getSpanId(), List.of());
        if (depth >= maxDepth && !childRaws.isEmpty()) {
            return toNode(current, List.of(), childRaws.size());
        }

        List<SpanNode> children = childRaws.stream()
                .map(child -> pruneRecursive(child, childrenByParent, depth + 1, maxDepth))
                .toList();
        return toNode(current, children, 0);
    }

    private static SpanNode toNode(SpanRaw raw, List<SpanNode> children, int prunedChildCount) {
        return SpanNode.builder()
                .spanId(raw.getSpanId())
                .service(raw.getService())
                .operationName(raw.getOperationName())
                .durationMs(raw.getDurationMs())
                .children(children)
                .prunedChildCount(prunedChildCount)
                .build();
    }

    public static boolean isTruncated(TraceTreeVO traceTree) {
        return traceTree != null && containsPrunedNode(traceTree.getRoot());
    }

    private static boolean containsPrunedNode(SpanNode node) {
        if (node == null) {
            return false;
        }
        if (node.getPrunedChildCount() > 0) {
            return true;
        }
        return node.getChildren() != null && node.getChildren().stream().anyMatch(TraceTreeUtil::containsPrunedNode);
    }
}
