import visitor.GJDepthFirst;
import syntaxtree.*;
import java.util.*;

public class MiniRAEmitter extends GJDepthFirst<Void, Void> {

    private final StringBuilder output = new StringBuilder();
    private final Map<String, LivenessAndAllocator.FunctionAllocation> allocations;
    private LivenessAndAllocator.FunctionAllocation currentAlloc = null;

    private static final String R_SCRATCH_1 = "v0";
    private static final String R_SCRATCH_2 = "v1";

    public MiniRAEmitter(Map<String, LivenessAndAllocator.FunctionAllocation> allocations) {
        this.allocations = allocations;
    }

    public String getOutput() {
        return output.toString();
    }
    
    private boolean isSpilled(String loc) {
        return loc.startsWith("SPILLED");
    }
    
    private boolean isRegister(String loc) {
        if (loc == null || loc.isEmpty()) return false;
        char c = loc.charAt(0);
        return (c == 'v' || c == 'a' || c == 's' || c == 't');
    }
    
    
    private String getLoc(Temp t) {
        int id = Integer.parseInt(t.f1.f0.toString());
        return currentAlloc.allocationMap.get(id);
    }
    
    private String getLoc(SimpleExp se) {
        Node n = se.f0.choice;
        if (n instanceof Temp) {
            return getLoc((Temp) n);
        } else if (n instanceof IntegerLiteral) {
            return ((IntegerLiteral) n).f0.toString();
        } else if (n instanceof Label) {
            return ((Label) n).f0.toString();
        }
        return "???_SimpleExp_???";
    }
    
    private String loadToReg(String loc, String targetReg) {
        if (isRegister(loc)) {
            return loc;
        }
        
        if (isSpilled(loc)) {
            output.append("    ALOAD ").append(targetReg).append(" ").append(loc).append("\n");
            return targetReg;
        } 
        
        output.append("    MOVE ").append(targetReg).append(" ").append(loc).append("\n");
        return targetReg;
    }
    
    private void storeFromReg(String loc, String sourceReg) {
        if (isSpilled(loc)) {
            output.append("    ASTORE ").append(loc).append(" ").append(sourceReg).append("\n");
        } else {
            if (!loc.equals(sourceReg)) {
                output.append("    MOVE ").append(loc).append(" ").append(sourceReg).append("\n");
            }
        }
    }
    
    private void saveCalleeRegs() {
        if (currentAlloc == null || currentAlloc.sRegsUsed.isEmpty()) return;
        
        int stackIdx = currentAlloc.sRegSpillStart;
        for (String sReg : currentAlloc.sRegsUsed) {
            output.append("    ASTORE SPILLEDARG ").append(stackIdx)
                  .append(" ").append(sReg).append("\n");
            stackIdx++;
        }
    }

    private void restoreCalleeRegs() {
        if (currentAlloc == null || currentAlloc.sRegsUsed.isEmpty()) return;

        int stackIdx = currentAlloc.sRegSpillStart;
        for (String sReg : currentAlloc.sRegsUsed) {
            output.append("    ALOAD ").append(sReg).append(" SPILLEDARG ")
                  .append(stackIdx).append("\n");
            stackIdx++;
        }
    }

    @Override
    public Void visit(Goal n, Void argu) {
        currentAlloc = allocations.get("MAIN");
        output.append("MAIN [")
              .append(currentAlloc.numArgs).append("] [")
              .append(currentAlloc.numStackSlots).append("] [")
              .append(currentAlloc.maxArgsInCalls).append("]\n");
        
        saveCalleeRegs();
              
        n.f1.accept(this, argu);
        
        restoreCalleeRegs();
        
        output.append("END\n");
        output.append(currentAlloc.spilled ? "// SPILLED\n\n" : "// NOTSPILLED\n\n");

        n.f3.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(Procedure n, Void argu) {
        String funcName = n.f0.f0.toString();
        currentAlloc = allocations.get(funcName);

        output.append(funcName).append(" [")
              .append(currentAlloc.numArgs).append("] [")
              .append(currentAlloc.numStackSlots).append("] [")
              .append(currentAlloc.maxArgsInCalls).append("]\n");

        saveCalleeRegs();
        
        for (int i = 0; i < currentAlloc.numArgs && i < 4; i++) {
            String loc = currentAlloc.allocationMap.get(i);
            storeFromReg(loc, "a" + i);
        }

        n.f4.accept(this, argu);

        output.append("END\n");
        output.append(currentAlloc.spilled ? "// SPILLED\n\n" : "// NOTSPILLED\n\n");
        return null;
    }
    
    @Override
    public Void visit(StmtExp n, Void argu) {
        n.f1.accept(this, argu);
        
        String retLoc = getLoc(n.f3);
        String retReg = loadToReg(retLoc, R_SCRATCH_1);
        
        if (!retReg.equals(R_SCRATCH_1)) {
            output.append("    MOVE ").append(R_SCRATCH_1).append(" ").append(retReg).append("\n");
        }

        restoreCalleeRegs();
        
        return null;
    }

    @Override
    public Void visit(StmtList n, Void argu) {
        n.f0.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(Stmt n, Void argu) {
        n.f0.accept(this, argu);
        return null;
    }
    
    @Override
    public Void visit(Label n, Void argu) {
        output.append(n.f0.toString()).append("\n");
        return null;
    }

    @Override
    public Void visit(NoOpStmt n, Void argu) {
        output.append("    NOOP\n");
        return null;
    }

    @Override
    public Void visit(ErrorStmt n, Void argu) {
        output.append("    ERROR\n");
        return null;
    }

    @Override
    public Void visit(CJumpStmt n, Void argu) {
        String loc = getLoc(n.f1);
        String reg = loadToReg(loc, R_SCRATCH_1);
        String label = n.f2.f0.toString();
        
        output.append("    CJUMP ").append(reg).append(" ").append(label).append("\n");
        return null;
    }

    @Override
    public Void visit(JumpStmt n, Void argu) {
        String label = n.f1.f0.toString();
        output.append("    JUMP ").append(label).append("\n");
        return null;
    }

    @Override
    public Void visit(HStoreStmt n, Void argu) {
        String baseLoc = getLoc(n.f1);
        String srcLoc = getLoc(n.f3);
        String offset = n.f2.f0.toString();
        
        String baseReg = loadToReg(baseLoc, R_SCRATCH_1);
        String srcReg = loadToReg(srcLoc, R_SCRATCH_2);
        
        output.append("    HSTORE ").append(baseReg).append(" ").append(offset)
              .append(" ").append(srcReg).append("\n");
        return null;
    }

    @Override
    public Void visit(HLoadStmt n, Void argu) {
        String dstLoc = getLoc(n.f1);
        String baseLoc = getLoc(n.f2);
        String offset = n.f3.f0.toString();
        
        String baseReg = loadToReg(baseLoc, R_SCRATCH_1);
        
        output.append("    HLOAD ").append(R_SCRATCH_2).append(" ").append(baseReg)
              .append(" ").append(offset).append("\n");
              
        storeFromReg(dstLoc, R_SCRATCH_2);
        return null;
    }

    @Override
    public Void visit(MoveStmt n, Void argu) {
        String dstLoc = getLoc(n.f1);
        Node exp = n.f2.f0.choice;
        
        if (exp instanceof SimpleExp) {
            String srcLoc = getLoc((SimpleExp) exp);
            if (isSpilled(dstLoc) && dstLoc.equals(srcLoc)) {
                return null; 
            }
            String srcReg = loadToReg(srcLoc, R_SCRATCH_1);
            storeFromReg(dstLoc, srcReg);
        }
        
        else if (exp instanceof BinOp) {
            BinOp op = (BinOp) exp;
            String opName = ((NodeToken) op.f0.f0.choice).tokenImage;
            String t1Loc = getLoc(op.f1);
            String seLoc = getLoc(op.f2);
            
            String t1Reg = loadToReg(t1Loc, R_SCRATCH_1);
            String seReg = loadToReg(seLoc, R_SCRATCH_2);
            
            output.append("    MOVE ").append(R_SCRATCH_1).append(" ")
                  .append(opName).append(" ").append(t1Reg).append(" ")
                  .append(seReg).append("\n");
            
            storeFromReg(dstLoc, R_SCRATCH_1);
        }
        
        else if (exp instanceof HAllocate) {
            HAllocate h = (HAllocate) exp;
            String sizeLoc = getLoc(h.f1);
            String sizeReg = loadToReg(sizeLoc, R_SCRATCH_1);
            
            output.append("    MOVE ").append(R_SCRATCH_2).append(" 8\n");
            output.append("    MOVE ").append(sizeReg).append(" PLUS ")
                  .append(sizeReg).append(" ").append(R_SCRATCH_2).append("\n");
            
            output.append("    MOVE ").append(R_SCRATCH_1).append(" HALLOCATE ")
                  .append(sizeReg).append("\n");
            
            storeFromReg(dstLoc, R_SCRATCH_1);
        }
        
        else if (exp instanceof Call) {
            Call call = (Call) exp;
            String funcLoc = getLoc(call.f1);
            
            String funcReg;
            if (isRegister(funcLoc)) {
                 funcReg = funcLoc;
            } else if (isSpilled(funcLoc)) {
                funcReg = loadToReg(funcLoc, R_SCRATCH_1);
            } else {
                funcReg = funcLoc;
            }
            
            for (int i = 0; i < call.f3.size(); i++) {
                Temp argTemp = (Temp) call.f3.elementAt(i);
                String argLoc = getLoc(argTemp);
                String argReg = loadToReg(argLoc, R_SCRATCH_2); 
                
                if (i < 4) {
                    output.append("    MOVE a").append(i).append(" ").append(argReg).append("\n");
                } else {
                    output.append("    PASSARG ").append(i - 3).append(" ").append(argReg).append("\n");
                }
            }
            
            output.append("    CALL ").append(funcReg).append("\n");
            
            storeFromReg(dstLoc, R_SCRATCH_1);
        }
        return null;
    }

    @Override
    public Void visit(PrintStmt n, Void argu) {
        String loc = getLoc(n.f1);
        String reg = loadToReg(loc, R_SCRATCH_1);
        output.append("    PRINT ").append(reg).append("\n");
        return null;
    }
}