import visitor.GJDepthFirst;
import syntaxtree.*;
import java.util.*;

public class CFGBuilderVisitor extends GJDepthFirst<Void, Void> {
    public static class CFGNode {
        public int id;
        public Set<Integer> use = new HashSet<>();
        public Set<Integer> def = new HashSet<>();
        public Set<Integer> succ = new HashSet<>();
        public Set<Integer> pred = new HashSet<>();
        public String labelTarget = null;    
        public boolean isUnconditionalJump = false;
        public boolean isCall = false;
        
        public boolean isMove = false; 
    }

    public static class FunctionCFG {
        public String name;
        public int numArgs;
        public int maxArgsInCalls = 0; 
        public List<CFGNode> nodes = new ArrayList<>();
        public Map<String, Integer> labelToNode = new HashMap<>();
        public Set<Integer> allTemps = new HashSet<>();
    }

    public Map<String, FunctionCFG> functions = new HashMap<>();
    private FunctionCFG currentFunc = null;
    private CFGNode currentNode = null;
    private int stmtCounter = 0;
    private String currentLabel = null;

    @Override
    public Void visit(Goal n, Void argu) {
        currentFunc = new FunctionCFG();
        currentFunc.name = "MAIN";
        currentFunc.numArgs = 0;
        stmtCounter = 0;
        currentLabel = null;
        n.f1.accept(this, argu); 
        buildEdges(currentFunc);
        functions.put(currentFunc.name, currentFunc);
        n.f3.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(Procedure n, Void argu) {
        currentFunc = new FunctionCFG();
        currentFunc.name = n.f0.f0.toString();
        currentFunc.numArgs = Integer.parseInt(n.f2.f0.toString());
        stmtCounter = 0;
        currentLabel = null;
        for (int i = 0; i < currentFunc.numArgs; i++) 
            currentFunc.allTemps.add(i); 
        n.f4.accept(this, argu); 
        buildEdges(currentFunc);
        functions.put(currentFunc.name, currentFunc);
        return null;
    }

    @Override
    public Void visit(StmtList n, Void argu) {
        n.f0.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(Stmt n, Void argu) {
        currentNode = new CFGNode();
        currentNode.id = stmtCounter++;
        n.f0.accept(this, argu); 
        currentFunc.nodes.add(currentNode);
        if (currentLabel != null) {
            currentFunc.labelToNode.put(currentLabel, currentNode.id);
            currentLabel = null;
        }
        return null;
    }
    
    @Override
    public Void visit(StmtExp n, Void argu) {
        n.f1.accept(this, argu); 
        currentNode = new CFGNode();
        currentNode.id = stmtCounter++;
        collectSimpleExpUses(n.f3);
        currentFunc.nodes.add(currentNode);
        return null;
    }

    @Override
    public Void visit(Label n, Void argu) {
        currentLabel = n.f0.toString();
        return null;
    }

    @Override
    public Void visit(NoOpStmt n, Void argu) { return null; }

    @Override
    public Void visit(ErrorStmt n, Void argu) { return null; }

    @Override
    public Void visit(CJumpStmt n, Void argu) {
        addTempUse(n.f1); 
        currentNode.labelTarget = n.f2.f0.toString();
        return null;
    }

    @Override
    public Void visit(JumpStmt n, Void argu) {
        currentNode.labelTarget = n.f1.f0.toString();
        currentNode.isUnconditionalJump = true;
        return null;
    }

    @Override
    public Void visit(HStoreStmt n, Void argu) {
        addTempUse(n.f1); 
        addTempUse(n.f3); 
        return null;
    }

    @Override
    public Void visit(HLoadStmt n, Void argu) {
        addTempDef(n.f1);  
        addTempUse(n.f2); 
        return null;
    }

    @Override
    public Void visit(MoveStmt n, Void argu) {
        addTempDef(n.f1); 
        
        if (n.f2.f0.choice instanceof Call) {
            currentNode.isCall = true;
        } 
        else if (n.f2.f0.choice instanceof SimpleExp) {
            Node simple = ((SimpleExp) n.f2.f0.choice).f0.choice;
            if (simple instanceof Temp) {
                currentNode.isMove = true; 
            }
        }
        
        collectExpUses(n.f2); 
        return null;
    }

    @Override
    public Void visit(PrintStmt n, Void argu) {
        collectSimpleExpUses(n.f1);
        return null;
    }

    @Override
    public Void visit(Call n, Void argu) {
        collectSimpleExpUses(n.f1); 
        int numArgs = n.f3.size();
        if (numArgs > currentFunc.maxArgsInCalls)
            currentFunc.maxArgsInCalls = numArgs;
        for (Node arg : n.f3.nodes)
            addTempUse((Temp) arg);
        return null;
    }

    @Override
    public Void visit(HAllocate n, Void argu) {
        collectSimpleExpUses(n.f1);
        return null;
    }

    @Override
    public Void visit(BinOp n, Void argu) {
        addTempUse(n.f1);
        collectSimpleExpUses(n.f2);
        return null;
    }
    
    private void addTempUse(Temp t) {
        int id = Integer.parseInt(t.f1.f0.toString());
        currentNode.use.add(id);
        currentFunc.allTemps.add(id);
    }
    
    private void addTempDef(Temp t) {
        int id = Integer.parseInt(t.f1.f0.toString());
        currentNode.def.add(id);
        currentFunc.allTemps.add(id);
    }

    private void collectExpUses(Exp e) {
        Node n = e.f0.choice;
        if (n instanceof Call) visit((Call) n, null);
        else if (n instanceof HAllocate) visit((HAllocate) n, null);
        else if (n instanceof BinOp) visit((BinOp) n, null);
        else if (n instanceof SimpleExp) collectSimpleExpUses((SimpleExp) n);
    }

    private void collectSimpleExpUses(SimpleExp e) {
        Node n = e.f0.choice;
        if (n instanceof Temp)
            addTempUse((Temp) n);
    }

    private void buildEdges(FunctionCFG func) {
        for (int i = 0; i < func.nodes.size(); i++) {
            CFGNode node = func.nodes.get(i);
            if (node.labelTarget != null) {
                Integer targetNodeId = func.labelToNode.get(node.labelTarget);
                if (targetNodeId != null) {
                    node.succ.add(targetNodeId);
                    func.nodes.get(targetNodeId).pred.add(i);
                }
            }
            if (!node.isUnconditionalJump) {
                if (i + 1 < func.nodes.size()) {
                    node.succ.add(i + 1);
                    func.nodes.get(i + 1).pred.add(i);
                }
            }
        }
    }
}