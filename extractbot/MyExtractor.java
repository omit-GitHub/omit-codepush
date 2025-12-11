package extractbot;

import extractbot.tool.BaseExtractor;
import cn.edu.whu.cstar.testingcourse.cfgparser.CfgNodeVisitor;
import cn.edu.whu.cstar.testingcourse.cfgparser.LogItem;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.util.*;

public class MyExtractor extends BaseExtractor {

    /**
     * 用于保存解析到的 CFG 节点信息。位置字段（startX）用于保持源码顺序，kind/parent 用于识别循环结构。
     */
    static class NodeInfo {
        final int id;
        final int parent;
        final int height;
        final int startX;
        final String kind;
        final String code;

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

        Field kindField = null;
        String[] kindCandidates = {"strType", "nodeType", "type"};
        for (String name : kindCandidates) {
            try {
                kindField = LogItem.class.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) {}
        }

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

            if ((kind == null || kind.isEmpty()) && code != null) {
                int at = code.indexOf('@');
                if (at >= 0) {
                    kind = code.substring(0, at);
                    code = code.substring(at + 1);
                }
            }

            nodeInfos.add(new NodeInfo(cur, parent, height, startX, kind, code));
            idx++;
        }

        return nodeInfos;
    }

    private List<int[]> buildCFGEdges(List<NodeInfo> nodeInfos) {
        Map<Integer, NodeInfo> nodeMap = new HashMap<>();
        for (NodeInfo n : nodeInfos) nodeMap.put(n.id, n);

        Map<Integer, List<NodeInfo>> childrenMap = new HashMap<>();
        for (NodeInfo node : nodeInfos) {
            if (node.parent != -1) {
                childrenMap.computeIfAbsent(node.parent, k -> new ArrayList<>()).add(node);
            }
        }
        for (List<NodeInfo> list : childrenMap.values()) {
            list.sort(Comparator.comparingInt(n -> n.startX));
        }

        List<NodeInfo> roots = new ArrayList<>();
        for (NodeInfo node : nodeInfos) {
            if (node.parent == -1 && (node.kind == null || !node.kind.contains("pseudo-return"))) {
                roots.add(node);
            }
        }
        roots.sort(Comparator.comparingInt(n -> n.startX));

        NodeInfo pseudoReturn = null;
        for (NodeInfo n : nodeInfos) {
            if (n.kind.contains("pseudo-return")) {
                pseudoReturn = n;
                break;
            }
        }

        List<int[]> edges = new ArrayList<>();
        Set<String> edgeSet = new HashSet<>();

        for (NodeInfo node : nodeInfos) {
            switch (classify(node.kind)) {
                case IF:
                    handleIf(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case FOR:
                    handleFor(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case WHILE:
                    handleWhile(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case DO_WHILE:
                    handleDoWhile(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case SWITCH:
                    handleSwitch(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case BREAK:
                    handleBreak(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case CONTINUE:
                    handleContinue(node, nodeMap, childrenMap, roots, pseudoReturn, edges, edgeSet);
                    break;
                case RETURN:
                    handleReturn(node, pseudoReturn, edges, edgeSet);
                    break;
                default:
                    break;
            }
        }

        for (Map.Entry<Integer, List<NodeInfo>> entry : childrenMap.entrySet()) {
            NodeInfo parent = nodeMap.get(entry.getKey());
            if (parent != null && isControl(parent.kind)) {
                continue;
            }
            List<NodeInfo> siblings = entry.getValue();
            siblings.sort(Comparator.comparingInt(n -> n.startX));
            connectSiblings(siblings, nodeMap, childrenMap, edges, edgeSet);
        }
        connectSiblings(roots, nodeMap, childrenMap, edges, edgeSet);

        return edges;
    }

    private enum NodeKind {IF, FOR, WHILE, DO_WHILE, SWITCH, BREAK, CONTINUE, RETURN, OTHER}

    private NodeKind classify(String kind) {
        if (kind == null) return NodeKind.OTHER;
        if (kind.startsWith("if-statement")) return NodeKind.IF;
        if (kind.startsWith("for-statement") || kind.startsWith("enhanced-for")) return NodeKind.FOR;
        if (kind.startsWith("while-statement")) return NodeKind.WHILE;
        if (kind.startsWith("do-while")) return NodeKind.DO_WHILE;
        if (kind.startsWith("switch-statement")) return NodeKind.SWITCH;
        if (kind.startsWith("break")) return NodeKind.BREAK;
        if (kind.startsWith("continue")) return NodeKind.CONTINUE;
        if (kind.startsWith("return")) return NodeKind.RETURN;
        return NodeKind.OTHER;
    }

    private boolean isControl(String kind) {
        NodeKind nk = classify(kind);
        return nk != NodeKind.OTHER;
    }

    private void handleIf(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                          Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                          List<int[]> edges, Set<String> edgeSet) {
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        NodeInfo cond = null, thenB = null, elseB = null;
        for (NodeInfo c : children) {
            if (c.kind.startsWith("if-condition")) cond = c;
            else if (c.kind.startsWith("if-then")) thenB = c;
            else if (c.kind.startsWith("if-else")) elseB = c;
        }
        if (cond == null) return;
        addEdge(edges, edgeSet, node.id, cond.id);
        NodeInfo after = findNext(node, nodeMap, childrenMap, roots, pseudoReturn);
        if (thenB != null) {
            addEdge(edges, edgeSet, cond.id, thenB.id);
            NodeInfo thenExit = findBlockExit(thenB, nodeMap, childrenMap, pseudoReturn);
            if (after != null && thenExit != null) addEdge(edges, edgeSet, thenExit.id, after.id);
        } else if (after != null) {
            addEdge(edges, edgeSet, cond.id, after.id);
        }
        if (elseB != null) {
            addEdge(edges, edgeSet, cond.id, elseB.id);
            NodeInfo elseExit = findBlockExit(elseB, nodeMap, childrenMap, pseudoReturn);
            if (after != null && elseExit != null) addEdge(edges, edgeSet, elseExit.id, after.id);
        } else if (after != null) {
            addEdge(edges, edgeSet, cond.id, after.id);
        }
    }

    private void handleFor(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                           Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                           List<int[]> edges, Set<String> edgeSet) {
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        NodeInfo cond = null, body = null, update = null;
        for (NodeInfo c : children) {
            if (c.kind.startsWith("for-condition")) cond = c;
            else if (c.kind.startsWith("for-body")) body = c;
            else if (c.kind.startsWith("for-update")) update = c;
        }
        if (cond == null) return;
        addEdge(edges, edgeSet, node.id, cond.id);
        if (body != null) addEdge(edges, edgeSet, cond.id, body.id);
        if (body != null) {
            addEdge(edges, edgeSet, body.id, cond.id);
        }
        NodeInfo after = findNext(node, nodeMap, childrenMap, roots, pseudoReturn);
        if (after != null) addEdge(edges, edgeSet, cond.id, after.id);
    }

    private void handleWhile(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                             Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                             List<int[]> edges, Set<String> edgeSet) {
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        NodeInfo cond = null, body = null;
        for (NodeInfo c : children) {
            if (c.kind.startsWith("while-condition")) cond = c;
            else if (c.kind.startsWith("while-body")) body = c;
        }
        if (cond == null) return;
        addEdge(edges, edgeSet, node.id, cond.id);
        if (body != null) {
            addEdge(edges, edgeSet, cond.id, body.id);
            addEdge(edges, edgeSet, body.id, cond.id);
        }
        NodeInfo after = findNext(node, nodeMap, childrenMap, roots, pseudoReturn);
        if (after != null) addEdge(edges, edgeSet, cond.id, after.id);
    }

    private void handleDoWhile(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                               Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                               List<int[]> edges, Set<String> edgeSet) {
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        NodeInfo cond = null, body = null;
        for (NodeInfo c : children) {
            if (c.kind.contains("do-condition")) cond = c;
            else if (c.kind.contains("do-body")) body = c;
        }
        if (body != null) addEdge(edges, edgeSet, node.id, body.id);
        if (body != null && cond != null) addEdge(edges, edgeSet, body.id, cond.id);
        if (cond != null) {
            addEdge(edges, edgeSet, cond.id, body != null ? body.id : cond.id);
            NodeInfo after = findNext(node, nodeMap, childrenMap, roots, pseudoReturn);
            if (after != null) addEdge(edges, edgeSet, cond.id, after.id);
        }
    }

    private void handleSwitch(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                              Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                              List<int[]> edges, Set<String> edgeSet) {
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        if (children.isEmpty()) return;
        NodeInfo first = children.get(0);
        addEdge(edges, edgeSet, node.id, first.id);
        for (int i = 0; i < children.size() - 1; i++) {
            addEdge(edges, edgeSet, children.get(i).id, children.get(i + 1).id);
        }
        NodeInfo after = findNext(node, nodeMap, childrenMap, roots, pseudoReturn);
        if (after != null) {
            for (NodeInfo c : children) addEdge(edges, edgeSet, c.id, after.id);
        }
    }

    private void handleBreak(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                             Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                             List<int[]> edges, Set<String> edgeSet) {
        NodeInfo target = findBreakTarget(node, nodeMap, childrenMap, roots, pseudoReturn);
        if (target != null) addEdge(edges, edgeSet, node.id, target.id);
    }

    private void handleContinue(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                                Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn,
                                List<int[]> edges, Set<String> edgeSet) {
        NodeInfo target = findContinueTarget(node, nodeMap, childrenMap);
        if (target != null) addEdge(edges, edgeSet, node.id, target.id);
    }

    private void handleReturn(NodeInfo node, NodeInfo pseudoReturn, List<int[]> edges, Set<String> edgeSet) {
        if (pseudoReturn != null) {
            addEdge(edges, edgeSet, node.id, pseudoReturn.id);
        }
    }

    private NodeInfo findBreakTarget(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                                     Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn) {
        NodeInfo cur = nodeMap.get(node.parent);
        while (cur != null) {
            if (classify(cur.kind) == NodeKind.SWITCH || classify(cur.kind) == NodeKind.FOR
                    || classify(cur.kind) == NodeKind.WHILE || classify(cur.kind) == NodeKind.DO_WHILE) {
                return findNext(cur, nodeMap, childrenMap, roots, pseudoReturn);
            }
            cur = nodeMap.get(cur.parent);
        }
        return pseudoReturn;
    }

    private NodeInfo findContinueTarget(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                                        Map<Integer, List<NodeInfo>> childrenMap) {
        NodeInfo cur = nodeMap.get(node.parent);
        while (cur != null) {
            if (classify(cur.kind) == NodeKind.FOR) {
                for (NodeInfo child : childrenMap.getOrDefault(cur.id, Collections.emptyList())) {
                    if (child.kind.startsWith("for-update")) return child;
                    if (child.kind.startsWith("for-condition")) return child;
                }
            } else if (classify(cur.kind) == NodeKind.WHILE || classify(cur.kind) == NodeKind.DO_WHILE) {
                for (NodeInfo child : childrenMap.getOrDefault(cur.id, Collections.emptyList())) {
                    if (child.kind.contains("condition")) return child;
                }
            }
            cur = nodeMap.get(cur.parent);
        }
        return null;
    }

    private void connectSiblings(List<NodeInfo> siblings, Map<Integer, NodeInfo> nodeMap,
                                 Map<Integer, List<NodeInfo>> childrenMap, List<int[]> edges,
                                 Set<String> edgeSet) {
        if (siblings == null || siblings.size() < 2) return;
        for (int i = 0; i < siblings.size() - 1; i++) {
            NodeInfo cur = siblings.get(i);
            NodeInfo nxt = siblings.get(i + 1);

            NodeKind kind = classify(cur.kind);
            if (kind == NodeKind.BREAK || kind == NodeKind.CONTINUE || kind == NodeKind.RETURN) {
                continue;
            }

            List<NodeInfo> exits = normalExits(cur, childrenMap, nodeMap, new HashSet<>());
            NodeInfo entry = nxt;
            for (NodeInfo ex : exits) {
                if (ex == null) continue;
                addEdge(edges, edgeSet, ex.id, entry.id);
            }
        }
    }

    private NodeInfo firstExecutable(NodeInfo node, Map<Integer, List<NodeInfo>> childrenMap,
                                     Map<Integer, NodeInfo> nodeMap, Set<Integer> visiting) {
        if (node == null || visiting.contains(node.id)) return node;
        visiting.add(node.id);
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        NodeKind nk = classify(node.kind);
        if (nk == NodeKind.IF || nk == NodeKind.WHILE || nk == NodeKind.FOR) {
            for (NodeInfo c : children) {
                if (c.kind.contains("condition")) return c;
            }
        } else if (nk == NodeKind.DO_WHILE) {
            for (NodeInfo c : children) {
                if (c.kind.contains("do-body")) return c;
            }
        } else if (nk == NodeKind.SWITCH) {
            if (!children.isEmpty()) return children.get(0);
        }

        if (!children.isEmpty()) {
            return firstExecutable(children.get(0), childrenMap, nodeMap, visiting);
        }
        return node;
    }

    private List<NodeInfo> normalExits(NodeInfo node, Map<Integer, List<NodeInfo>> childrenMap,
                                       Map<Integer, NodeInfo> nodeMap, Set<Integer> visiting) {
        List<NodeInfo> exits = new ArrayList<>();
        if (node == null) return exits;
        if (visiting.contains(node.id)) return exits;
        visiting.add(node.id);
        NodeKind nk = classify(node.kind);
        if (nk == NodeKind.BREAK || nk == NodeKind.CONTINUE || nk == NodeKind.RETURN) {
            return exits;
        }
        List<NodeInfo> children = childrenMap.getOrDefault(node.id, Collections.emptyList());
        switch (nk) {
            case IF:
                NodeInfo thenB = null, elseB = null, cond = null;
                for (NodeInfo c : children) {
                    if (c.kind.contains("condition")) cond = c;
                    else if (c.kind.contains("if-then")) thenB = c;
                    else if (c.kind.contains("if-else")) elseB = c;
                }
                if (thenB != null) exits.addAll(normalExits(thenB, childrenMap, nodeMap, visiting));
                if (elseB != null) exits.addAll(normalExits(elseB, childrenMap, nodeMap, visiting));
                if (exits.isEmpty() && cond != null) exits.add(cond);
                break;
            case FOR:
            case WHILE:
                for (NodeInfo c : children) {
                    if (c.kind.contains("condition")) exits.add(c);
                }
                if (exits.isEmpty()) exits.add(node);
                break;
            case DO_WHILE:
                for (NodeInfo c : children) {
                    if (c.kind.contains("condition")) exits.add(c);
                }
                if (exits.isEmpty()) exits.add(node);
                break;
            case SWITCH:
                if (!children.isEmpty()) {
                    exits.add(children.get(children.size() - 1));
                } else {
                    exits.add(node);
                }
                break;
            default:
                if (!children.isEmpty()) {
                    exits.addAll(normalExits(children.get(children.size() - 1), childrenMap, nodeMap, visiting));
                } else {
                    exits.add(node);
                }
                break;
        }
        return exits;
    }

    private NodeInfo findBlockExit(NodeInfo block, Map<Integer, NodeInfo> nodeMap,
                                   Map<Integer, List<NodeInfo>> childrenMap, NodeInfo pseudoReturn) {
        List<NodeInfo> children = childrenMap.getOrDefault(block.id, Collections.emptyList());
        if (children.isEmpty()) return block;
        return children.get(children.size() - 1);
    }

    private NodeInfo findNext(NodeInfo node, Map<Integer, NodeInfo> nodeMap,
                              Map<Integer, List<NodeInfo>> childrenMap, List<NodeInfo> roots, NodeInfo pseudoReturn) {
        NodeInfo parent = nodeMap.get(node.parent);
        List<NodeInfo> siblings = parent == null ? null : childrenMap.get(parent.id);
        if (siblings != null) {
            for (int i = 0; i < siblings.size(); i++) {
                if (siblings.get(i).id == node.id && i + 1 < siblings.size()) {
                    return siblings.get(i + 1);
                }
            }
        }
        if (parent == null) {
            for (int i = 0; i < roots.size(); i++) {
                if (roots.get(i).id == node.id && i + 1 < roots.size()) return roots.get(i + 1);
            }
        }
        if (parent != null) return findNext(parent, nodeMap, childrenMap, roots, pseudoReturn);
        return null;
    }

    private void addEdge(List<int[]> edges, Set<String> edgeSet, int from, int to) {
        String k = from + "->" + to;
        if (edgeSet.add(k)) {
            edges.add(new int[]{from, to});
        }
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