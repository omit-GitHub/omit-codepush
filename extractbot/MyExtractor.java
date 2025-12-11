package extractbot;

import extractbot.tool.BaseExtractor;
import cn.edu.whu.cstar.testingcourse.cfgparser.CfgNodeVisitor;
import cn.edu.whu.cstar.testingcourse.cfgparser.LogItem;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MyExtractor extends BaseExtractor {

    // 节点信息类
    /**
     * 用于保存解析到的 CFG 节点信息。位置字段（startX）用于保持源码顺序，kind/parent 用于识别循环结构。
     */
    static class NodeInfo {
        final int id;
        final int parent;
        final int height;
        final int startX;
        final String kind;
        final String code;    // 节点对应的源码片段，例如 "for (int i=0; i < length; i++) {}"

        NodeInfo(int id, int parent, int height, int startX, String kind, String code) {
            this.id = id;
            this.parent = parent;
            this.height = height;
            this.startX = startX;
            this.kind = kind == null ? "" : kind;
            this.code = code == null ? "" : code;
        }
    }

    @Override
    public int[][] getControlFlowGraphInArray(String pathFile, String methodName) {
        if (pathFile == null || methodName == null) {
            throw new IllegalArgumentException("pathFile 或 methodName 不能为 null");
        }


        // 1. 读取源文件
        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get(pathFile)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new int[0][0];
        }

        ASTParser parser = ASTParser.newParser(AST.JLS8);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        @SuppressWarnings("unchecked")
        List<TypeDeclaration> types = unit.types();
        if (types == null || types.isEmpty()) {
            return new int[0][0];
        }

        MethodDeclaration targetMethod = null;
        outer:
        for (TypeDeclaration type : types) {
            for (MethodDeclaration m : type.getMethods()) {
                if (m.getName().getIdentifier().equals(methodName)) {
                    targetMethod = m;
                    break outer;
                }
            }
        }

        if (targetMethod == null || targetMethod.getBody() == null) {
            return new int[0][0];
        }

        // 重置访问器中的静态计数器，保证每次调用编号一致
        try {
            Field indexField = CfgNodeVisitor.class.getDeclaredField("indexNode");
            indexField.setAccessible(true);
            indexField.setInt(null, 0);

            Field counterField = CfgNodeVisitor.class.getDeclaredField("counterReturnStmt");
            counterField.setAccessible(true);
            counterField.setInt(null, 0);
        } catch (Exception ignored) {}

        CfgNodeVisitor visitor = new CfgNodeVisitor(targetMethod, unit);
        targetMethod.getBody().accept(visitor);

        if (visitor.getCounterReturnStmt() == 0) {
            visitor.addPseudoReturnStmt();
        }
        visitor.updateParent();

        try {
            Field listField = CfgNodeVisitor.class.getDeclaredField("listLogItem");
            listField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<LogItem> items = (List<LogItem>) listField.get(visitor);

            if (items == null || items.isEmpty()) {
                return new int[0][0];
            }

            List<NodeInfo> nodeInfos = parseNodeInfos(items);
            List<int[]> edges = buildCFGEdges(nodeInfos);

            int[][] result = new int[edges.size()][2];
            for (int i = 0; i < edges.size(); i++) {
                result[i][0] = edges.get(i)[0];
                result[i][1] = edges.get(i)[1];
            }


            return result;

        } catch (Exception e) {
            return new int[0][0];
        }
    }


    private List<NodeInfo> parseNodeInfos(List<LogItem> items) throws Exception {
        List<NodeInfo> nodeInfos = new ArrayList<>();

        Field curField    = LogItem.class.getDeclaredField("indexNodeCurrent");
        Field parentField = LogItem.class.getDeclaredField("indexNodeParent");
        Field heightField = LogItem.class.getDeclaredField("height");

        Field startXField = null;
        String[] startXCandidates = {"startX", "startx", "start_col", "startColumn", "start_x", "position"};
        for (String name : startXCandidates) {
            try {
                startXField = LogItem.class.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) {}
        }

        // 2) 可能的“类型字段名”和“内容字段名”
        Field kindField = null;

        String[] kindCandidates = {"strType", "nodeType", "type"};
        for (String name : kindCandidates) {
            try {
                kindField = LogItem.class.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) {}
        }

        // 内容字段名候选
        Field codeField = null;
        String[] codeCandidates = {"content", "strContent", "code"};
        for (String name : codeCandidates) {
            try {
                codeField = LogItem.class.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) {}
        }

        curField.setAccessible(true);
        parentField.setAccessible(true);
        heightField.setAccessible(true);
        if (startXField != null) startXField.setAccessible(true);
        if (kindField != null) kindField.setAccessible(true);
        if (codeField != null) codeField.setAccessible(true);

        int idx = 0;
        for (LogItem item : items) {
            int cur    = (Integer) curField.get(item);
            int parent = (Integer) parentField.get(item);
            int height = (Integer) heightField.get(item);

            int startX = idx;
            if (startXField != null) {
                try {
                    startX = (Integer) startXField.get(item);
                } catch (Exception ignored) {}
            }

            String kind = "";
            if (kindField != null) {
                Object obj = kindField.get(item);
                if (obj != null) {
                    kind = obj.toString();
                }
            }

            String code = "";
            if (codeField != null) {
                Object obj = codeField.get(item);
                if (obj != null) {
                    code = obj.toString();
                }
            }

            // 如果 kind 为空，而 code 里包含 "@"，尝试拆出前缀当作 kind
            if ((kind == null || kind.isEmpty()) && code != null) {
                int at = code.indexOf('@');
                if (at >= 0) {
                    kind = code.substring(0, at);
                    code = code.substring(at + 1);  // 去掉前缀后的源码字符串
                }
            }

            nodeInfos.add(new NodeInfo(cur, parent, height, startX, kind, code));
            idx++;
        }

        return nodeInfos;
    }

    private List<int[]> buildCFGEdges(List<NodeInfo> nodeInfos) {
        List<int[]> edges = new ArrayList<>();

        // 1. 按 parent 分组：构造 childrenMap
        Map<Integer, List<NodeInfo>> childrenMap = new HashMap<>();
        for (NodeInfo node : nodeInfos) {
            if (node.parent != -1) {
                childrenMap.computeIfAbsent(node.parent, k -> new ArrayList<>()).add(node);
            }
        }

        // 2. 顶层节点（parent == -1）
        List<NodeInfo> topNodes = new ArrayList<>();
        for (NodeInfo node : nodeInfos) {
            if (node.parent == -1) {
                topNodes.add(node);
            }
        }

        // 按 startX 排序顶层节点，模拟代码从左到右的顺序
        topNodes.sort(Comparator.comparingInt(n -> n.startX));

        // 3. 处理每个 for-statement：内部边 + false 分支
        for (NodeInfo top : topNodes) {
            if (top.kind.startsWith("for-statement")) {
                List<NodeInfo> children = childrenMap.getOrDefault(top.id, Collections.emptyList());

                NodeInfo cond = null;
                NodeInfo body = null;
                for (NodeInfo child : children) {
                    if (child.kind.startsWith("for-condition")) {
                        cond = child;
                    } else if (child.kind.startsWith("for-body")) {
                        body = child;
                    }
                }
                if (cond == null || body == null) {
                    continue;
                }

                // loop -> cond
                edges.add(new int[]{top.id, cond.id});
                // cond -> body（true 分支）
                edges.add(new int[]{cond.id, body.id});
                // body -> cond（回边）
                edges.add(new int[]{body.id, cond.id});

                // false 分支：cond -> 下一个非循环顶层语句（且不是 pseudo-return）
                NodeInfo nextStmt = null;
                boolean seen = false;
                for (NodeInfo tn : topNodes) {
                    if (!seen) {
                        seen = tn.id == top.id;
                        continue;
                    }
                    if (tn.kind.startsWith("for-statement") || tn.kind.startsWith("pseudo-return")) {
                        continue;
                    }

                    nextStmt = tn;
                    break;
                }
                if (nextStmt != null) {
                    edges.add(new int[]{cond.id, nextStmt.id});
                }
            }
        }

        // 4. 非循环顶层语句之间的顺序边：当前为普通语句，后一个为 for-statement
        for (int i = 0; i < topNodes.size() - 1; i++) {
            NodeInfo cur = topNodes.get(i);
            NodeInfo nxt = topNodes.get(i + 1);

            if (!cur.kind.startsWith("for-statement")
                    && !cur.kind.startsWith("pseudo-return")
                    && !nxt.kind.startsWith("pseudo-return")) {
                edges.add(new int[]{cur.id, nxt.id});
            }
        }
        return edges;
    }



    @Override
    public int[][] getTestRequirementsInArray(String pathFile, String methodName) {
        int[][] cfg = getControlFlowGraphInArray(pathFile, methodName);
        if (cfg == null || cfg.length == 0) {
            System.out.println("Error. No test requirement is found.");
            return new int[0][0];
        }

        Map<Integer, List<Integer>> adj = buildAdj(cfg);
        Set<Integer> nodes = collectNodes(cfg);

        List<int[]> allPaths = enumerateAllSimplePathsAndCycles(adj, nodes);
        if (allPaths.isEmpty()) {
            System.out.println("Error. No test requirement is found.");
            return new int[0][0];
        }

        List<int[]> prime = new ArrayList<>();
        for (int i = 0; i < allPaths.size(); i++) {
            int[] p = allPaths.get(i);
            boolean isSub = false;
            for (int j = 0; j < allPaths.size(); j++) {
                if (i == j) continue;
                int[] q = allPaths.get(j);
                if (isSubpath(p, q)) {
                    isSub = true;
                    break;
                }
            }
            if (!isSub) {
                prime.add(p);
            }
        }

        int[][] result = new int[prime.size()][];
        for (int i = 0; i < prime.size(); i++) {
            result[i] = prime.get(i);
        }
        return result;
    }

    private Map<Integer, List<Integer>> buildAdj(int[][] edges) {
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int[] e : edges) {
            int from = e[0];
            int to = e[1];
            adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        return adj;
    }

    private Set<Integer> collectNodes(int[][] edges) {
        Set<Integer> nodes = new HashSet<>();
        for (int[] e : edges) {
            nodes.add(e[0]);
            nodes.add(e[1]);
        }
        return nodes;
    }

    private List<int[]> enumerateAllSimplePathsAndCycles(Map<Integer, List<Integer>> adj,
                                                         Set<Integer> nodes) {
        List<int[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int maxNode = -1;
        for (int v : nodes) {
            if (v > maxNode) maxNode = v;
        }
        if (maxNode < 0) return result;

        for (int start : nodes) {
            boolean[] visited = new boolean[maxNode + 1];
            List<Integer> path = new ArrayList<>();
            visited[start] = true;
            path.add(start);
            dfsEnumerate(start, path, visited, adj, result, seen);
        }
        return result;
    }

    private void dfsEnumerate(int start, List<Integer> path, boolean[] visited,
                              Map<Integer, List<Integer>> adj,
                              List<int[]> result, Set<String> seen) {
        int last = path.get(path.size() - 1);
        List<Integer> succs = adj.get(last);
        if (succs == null) return;

        for (int succ : succs) {
            if (!visited[succ]) {
                visited[succ] = true;
                path.add(succ);
                addPath(path, result, seen);
                dfsEnumerate(start, path, visited, adj, result, seen);
                path.remove(path.size() - 1);
                visited[succ] = false;
            } else if (succ == start && path.size() >= 2) {
                path.add(succ);
                addPath(path, result, seen);
                path.remove(path.size() - 1);
            }
        }
    }

    private void addPath(List<Integer> path, List<int[]> result, Set<String> seen) {
        String key = pathKeyFromList(path);
        if (!seen.contains(key)) {
            seen.add(key);
            result.add(toArray(path));
        }
    }

    private String pathKeyFromList(List<Integer> path) {
        StringBuilder sb = new StringBuilder();
        for (int v : path) {
            sb.append(v).append(",");
        }
        return sb.toString();
    }

    private int[] toArray(List<Integer> path) {
        int[] arr = new int[path.size()];
        for (int i = 0; i < path.size(); i++) {
            arr[i] = path.get(i);
        }
        return arr;
    }

    private boolean isSubpath(int[] small, int[] big) {
        if (small.length > big.length) return false;
        outer:
        for (int i = 0; i <= big.length - small.length; i++) {
            for (int j = 0; j < small.length; j++) {
                if (big[i + j] != small[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public int[][] getTestPathsInArray(String pathFile, String methodName) {
        int[][] cfg = getControlFlowGraphInArray(pathFile, methodName);
        if (cfg == null || cfg.length == 0) {
            System.out.println("Error. No test path is found.");
            return new int[0][0];
        }

        int[][] prime = getTestRequirementsInArray(pathFile, methodName);
        if (prime == null || prime.length == 0) {
            System.out.println("Error. No test path is found.");
            return new int[0][0];
        }

        Map<Integer, List<Integer>> adj = buildAdj(cfg);
        Set<Integer> nodes = collectNodes(cfg);

        int start = findEntryNode(cfg, nodes);
        int end = findExitNode(cfg, nodes);

        if (start == -1 || end == -1) {
            System.out.println("Error. No test path is found.");
            return new int[0][0];
        }

        List<int[]> testPaths = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int[] p : prime) {
            if (p == null || p.length == 0) continue;

            int pStart = p[0];
            int pEnd = p[p.length - 1];

            List<Integer> prefix = bfsPath(start, pStart, adj);
            List<Integer> suffix = bfsPath(pEnd, end, adj);

            if ((prefix == null || prefix.isEmpty()) &&
                    (suffix == null || suffix.isEmpty())) {
                if (pStart == start && pEnd == end) {
                    List<Integer> only = new ArrayList<>();
                    for (int v : p) only.add(v);
                    String key = pathKeyFromList(only);
                    if (!seen.contains(key)) {
                        seen.add(key);
                        testPaths.add(toArray(only));
                    }
                }
                continue;
            }

            List<Integer> path = new ArrayList<>();

            if (prefix != null && !prefix.isEmpty()) {
                path.addAll(prefix);
            }

            int startIdx = 0;
            if (!path.isEmpty() && path.get(path.size() - 1) == pStart) {
                startIdx = 1;
            }
            for (int i = startIdx; i < p.length; i++) {
                path.add(p[i]);
            }

            if (suffix != null && !suffix.isEmpty()) {
                int tNode = pEnd;
                int idx = 0;
                if (suffix.get(0) == tNode) {
                    idx = 1;
                }
                for (int i = idx; i < suffix.size(); i++) {
                    path.add(suffix.get(i));
                }
            }

            if (!path.isEmpty()) {
                String key = pathKeyFromList(path);
                if (!seen.contains(key)) {
                    seen.add(key);
                    testPaths.add(toArray(path));
                }
            }
        }

        if (testPaths.isEmpty()) {
            System.out.println("Error. No test path is found.");
            return new int[0][0];
        }

        int[][] result = new int[testPaths.size()][];
        for (int i = 0; i < testPaths.size(); i++) {
            result[i] = testPaths.get(i);
        }
        return result;
    }

    private int findEntryNode(int[][] cfg, Set<Integer> nodes) {
        Set<Integer> hasIn = new HashSet<>();
        for (int[] e : cfg) {
            hasIn.add(e[1]);
        }
        for (int v : nodes) {
            if (!hasIn.contains(v)) return v;
        }
        return -1;
    }

    private int findExitNode(int[][] cfg, Set<Integer> nodes) {
        Set<Integer> hasOut = new HashSet<>();
        for (int[] e : cfg) {
            hasOut.add(e[0]);
        }
        for (int v : nodes) {
            if (!hasOut.contains(v)) return v;
        }
        return -1;
    }

    private List<Integer> bfsPath(int start, int target, Map<Integer, List<Integer>> adj) {
        if (start == target) {
            List<Integer> path = new ArrayList<>();
            path.add(start);
            return path;
        }

        Queue<Integer> queue = new LinkedList<>();
        Map<Integer, Integer> prev = new HashMap<>();

        queue.add(start);
        prev.put(start, null);

        while (!queue.isEmpty()) {
            int u = queue.poll();
            List<Integer> succs = adj.get(u);
            if (succs == null) continue;

            for (int v : succs) {
                if (prev.containsKey(v)) continue;
                prev.put(v, u);
                if (v == target) {
                    List<Integer> path = new ArrayList<>();
                    Integer cur = v;
                    while (cur != null) {
                        path.add(cur);
                        cur = prev.get(cur);
                    }
                    Collections.reverse(path);
                    return path;
                }
                queue.add(v);
            }
        }
        return null;
    }
}
