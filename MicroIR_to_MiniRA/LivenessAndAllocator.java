import java.util.*;

public class LivenessAndAllocator {
    public static class FunctionAllocation {
        public String name;
        public int numArgs;
        public int numStackSlots;  
        public int maxArgsInCalls;
        public boolean spilled = false;
        public Map<Integer, String> allocationMap = new HashMap<>();
        public Set<String> sRegsUsed = new HashSet<>();
        public int sRegSpillStart;
    }

    private static final String[] REGISTERS = {
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7"
    };

    private static final int K = REGISTERS.length;

    public Map<String, FunctionAllocation> allocations = new HashMap<>();

    public void run(Map<String, CFGBuilderVisitor.FunctionCFG> functions) {
        for (CFGBuilderVisitor.FunctionCFG func : functions.values()) {
            Map<Integer, Set<Integer>> liveOut = computeLiveness(func);
            Map<Integer, Set<Integer>> interference = buildInterferenceGraph(func, liveOut);
            FunctionAllocation alloc = allocateRegisters(func, interference);
            allocations.put(func.name, alloc);
        }
    }

    private Map<Integer, Set<Integer>> computeLiveness(CFGBuilderVisitor.FunctionCFG func) {
        Map<Integer, Set<Integer>> in = new HashMap<>();
        Map<Integer, Set<Integer>> out = new HashMap<>();
        for (CFGBuilderVisitor.CFGNode node : func.nodes) {
            in.put(node.id, new HashSet<>());
            out.put(node.id, new HashSet<>());
        }
        boolean changed;
        do {
            changed = false;
            for (int i = func.nodes.size() - 1; i >= 0; i--) {
                CFGBuilderVisitor.CFGNode node = func.nodes.get(i);
                Set<Integer> newOut = new HashSet<>();
                for (int succId : node.succ)
                    newOut.addAll(in.get(succId));
                Set<Integer> newIn = new HashSet<>(node.use);
                Set<Integer> outMinusDef = new HashSet<>(newOut);
                outMinusDef.removeAll(node.def);
                newIn.addAll(outMinusDef);
                if (!in.get(node.id).equals(newIn) || !out.get(node.id).equals(newOut)) {
                    in.put(node.id, newIn);
                    out.put(node.id, newOut);
                    changed = true;
                }
            }
        } while (changed);
        return out;
    }

    private Map<Integer, Set<Integer>> buildInterferenceGraph(
            CFGBuilderVisitor.FunctionCFG func, 
            Map<Integer, Set<Integer>> liveOut) {
        
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (int t : func.allTemps)
            graph.put(t, new HashSet<>());

        for (int i = 0; i < func.numArgs; i++)
            for (int j = i + 1; j < func.numArgs; j++)
                if (graph.containsKey(i) && graph.containsKey(j))
                    addEdge(graph, i, j);

        for (CFGBuilderVisitor.CFGNode node : func.nodes) {
            Set<Integer> nodeLiveOut = liveOut.get(node.id);
            
            if (node.isMove) { 
                int y = -1;
                if (!node.use.isEmpty()) {
                    y = node.use.iterator().next();
                }

                for (int d : node.def) {
                    for (int live : nodeLiveOut) {
                        if (live != y && live != d) { 
                            addEdge(graph, d, live);
                        }
                    }
                }
            } else {
                for (int d : node.def) {
                    for (int live : nodeLiveOut) {
                        if (live != d) { 
                            addEdge(graph, d, live);
                        }
                    }
                }
            }
        }
        return graph;
    }
    
    private void addEdge(Map<Integer, Set<Integer>> graph, int u, int v) {
        graph.get(u).add(v);
        graph.get(v).add(u);
    }

    private FunctionAllocation allocateRegisters(CFGBuilderVisitor.FunctionCFG func, Map<Integer, Set<Integer>> graph) {
        FunctionAllocation result = new FunctionAllocation();
        result.name = func.name;
        result.numArgs = func.numArgs;
        result.maxArgsInCalls = func.maxArgsInCalls;
        for (int i = 4; i < func.numArgs; i++)
            result.allocationMap.put(i, "SPILLEDARG " + (i - 4));
        int numIncomingStackArgs = Math.max(0, func.numArgs - 4);
        int spillCount = 0;
        Set<Integer> tempsToColor = new HashSet<>(func.allTemps);
        tempsToColor.removeAll(result.allocationMap.keySet()); 
        Map<Integer, Integer> degree = new HashMap<>();
        for (int v : tempsToColor)
            degree.put(v, graph.getOrDefault(v, Collections.emptySet()).size());
        Stack<Integer> stack = new Stack<>();
        Set<Integer> nodes = new HashSet<>(tempsToColor);
        while (!nodes.isEmpty()) {
            Integer node = null;
            for (Integer v : nodes)
                if (degree.getOrDefault(v, 0) < K) {
                    node = v;
                    break;
                }
            if (node == null) {
                node = nodes.iterator().next();
                result.spilled = true;
            }
            nodes.remove(node);
            for (int neighbor : graph.getOrDefault(node, Collections.emptySet()))
                if (degree.containsKey(neighbor))
                    degree.put(neighbor, degree.get(neighbor) - 1);
            stack.push(node);
            degree.remove(node);
        }
        while (!stack.isEmpty()) {
            int v = stack.pop();
            Set<String> neighborRegs = new HashSet<>();   
            for (int neighbor : graph.getOrDefault(v, Collections.emptySet()))
                if (result.allocationMap.containsKey(neighbor)) {
                    String loc = result.allocationMap.get(neighbor);
                    if (!loc.startsWith("SPILLED"))
                        neighborRegs.add(loc);
                }
            String chosenReg = null;
            for (String r : REGISTERS)
                if (!neighborRegs.contains(r)) {
                    chosenReg = r;
                    break;
                }
            if (chosenReg != null) {
                result.allocationMap.put(v, chosenReg);
                result.sRegsUsed.add(chosenReg); 
            } else {
                String spillSlot = "SPILLEDARG " + (numIncomingStackArgs + spillCount);
                spillCount++;
                result.allocationMap.put(v, spillSlot);
            }
        }
        result.sRegSpillStart = numIncomingStackArgs + spillCount;
        result.numStackSlots = numIncomingStackArgs + spillCount + result.sRegsUsed.size() + Math.max(0, func.maxArgsInCalls - 4);
        return result;
    }
}