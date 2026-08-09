import syntaxtree.*;
import visitor.*;
import java.io.*;
import java.util.*;

public class MiniRAtoMIPS extends GJDepthFirst<String, String> {

    private final PrintStream out = System.out;

    private String getReg(Reg r) {
        if (r == null || r.f0 == null || r.f0.choice == null) return "";
        Object ch = r.f0.choice;
        if (ch instanceof NodeToken) return ((NodeToken) ch).toString();
        return ch.toString();
    }

    private String getInt(IntegerLiteral n) {
        if (n == null || n.f0 == null) return "0";
        return n.f0.toString();
    }

    private String getLabel(Label l) {
        if (l == null || l.f0 == null) return "";
        return l.f0.toString();
    }

    private int getSpilledIndex(SpilledArg s) {
        if (s == null) return 0;
        return Integer.parseInt(s.f1.f0.toString());
    }

    private String fmtRegName(String r) {
        if (r == null || r.isEmpty()) return "$zero";
        r = r.trim();
        if (r.startsWith("$")) return r;
        return "$" + r;
    }

    private void emitPrologue(int numSpilled, int maxCallArgs) {
        int outArgs = (maxCallArgs > 4) ? maxCallArgs - 4 : 0;
        int totalFrameSize = (numSpilled * 4) + (outArgs * 4) + 8;
        out.println("    addi $sp, $sp, -" + totalFrameSize);
        out.println("    sw $ra, " + (totalFrameSize - 4) + "($sp)");
        out.println("    sw $fp, " + (totalFrameSize - 8) + "($sp)");
    }

    private void emitEpilogue(int numSpilled, int maxCallArgs) {
        int outArgs = (maxCallArgs > 4) ? maxCallArgs - 4 : 0;
        int totalFrameSize = (numSpilled * 4) + (outArgs * 4) + 8;
        out.println("    lw $ra, " + (totalFrameSize - 4) + "($sp)");
        out.println("    lw $fp, " + (totalFrameSize - 8) + "($sp)");
        out.println("    addi $sp, $sp, " + totalFrameSize);
        out.println("    jr $ra");
    }

    private int getSpilledArgOffset(String argu, int idx) {
        if (argu == null)
            return idx * 4;
        String[] parts = argu.split(",");
        int numArgs = Integer.parseInt(parts[0]);
        int numSpilled = Integer.parseInt(parts[1]);
        int maxCallArgs = Integer.parseInt(parts[2]);

        int numStackArgs = (numArgs > 4) ? numArgs - 4 : 0;
        int offset;

        if (idx < numStackArgs) {
            int outArgs = (maxCallArgs > 4) ? maxCallArgs - 4 : 0;
            int totalFrameSize = (numSpilled * 4) + (outArgs * 4) + 8;
            offset = totalFrameSize + (idx * 4);
        } else {
            int outArgsSlots = (maxCallArgs > 4) ? maxCallArgs - 4 : 0;
            offset = (outArgsSlots * 4) + ((idx - numStackArgs) * 4);
        }
        return offset;
    }

    @Override
    public String visit(Goal n, String argu) {
        String numArgsStr = n.f2.f0.toString();
        String numSpilledStr = n.f5.f0.toString();
        String maxCallArgsStr = n.f8.f0.toString();
        int numSpilled = Integer.parseInt(numSpilledStr);
        int maxCallArgs = Integer.parseInt(maxCallArgsStr);

        String procInfo = numArgsStr + "," + numSpilledStr + "," + maxCallArgsStr;

        out.println(".text");
        out.println(".globl main");
        out.println("main:");

        emitPrologue(numSpilled, maxCallArgs); 
        n.f10.accept(this, procInfo); 

        out.println("    li $v0, 10");
        out.println("    syscall");

        n.f13.accept(this, null);
        return null;
    }

    @Override
    public String visit(StmtList n, String argu) {
        if (n == null || n.f0 == null) return null;
        Enumeration<Node> e = n.f0.elements();
        while (e.hasMoreElements()) {
            Node seq = e.nextElement();
            seq.accept(this, argu);
        }
        return null;
    }

    @Override
    public String visit(Procedure n, String argu) {
        String label = getLabel(n.f0);
        String numArgsStr = n.f2.f0.toString();
        String numSpilledStr = n.f5.f0.toString();
        String maxCallArgsStr = n.f8.f0.toString();
        int numSpilled = Integer.parseInt(numSpilledStr);
        int maxCallArgs = Integer.parseInt(maxCallArgsStr);
        
        String procInfo = numArgsStr + "," + numSpilledStr + "," + maxCallArgsStr;

        out.println(label + ":");
        emitPrologue(numSpilled, maxCallArgs); 
        n.f10.accept(this, procInfo); 
        emitEpilogue(numSpilled, maxCallArgs); 
        return null;
    }

    @Override
    public String visit(Stmt n, String argu) {
        return n.f0.accept(this, argu);
    }

    @Override
    public String visit(NoOpStmt n, String argu) {
        out.println("    nop");
        return null;
    }

    @Override
    public String visit(ErrorStmt n, String argu) {
        out.println("    li $v0, 10");
        out.println("    syscall");
        return null;
    }

    @Override
    public String visit(CJumpStmt n, String argu) {
        String reg = getReg(n.f1);
        String lab = getLabel(n.f2);
        out.println("    beqz " + fmtRegName(reg) + ", " + lab);
        return null;
    }

    @Override
    public String visit(JumpStmt n, String argu) {
        String lab = getLabel(n.f1);
        out.println("    j " + lab);
        return null;
    }

    @Override
    public String visit(HStoreStmt n, String argu) {
        String base = getReg(n.f1);
        String offset = getInt(n.f2);
        String src = getReg(n.f3);
        out.println("    sw " + fmtRegName(src) + ", " + offset + "(" + fmtRegName(base) + ")");
        return null;
    }

    @Override
    public String visit(HLoadStmt n, String argu) {
        String dest = getReg(n.f1);
        String base = getReg(n.f2);
        String offset = getInt(n.f3);
        out.println("    lw " + fmtRegName(dest) + ", " + offset + "(" + fmtRegName(base) + ")");
        return null;
    }

    @Override
    public String visit(MoveStmt n, String argu) {
        String dest = getReg(n.f1);
        n.f2.accept(this, dest);
        return null;
    }

    @Override
    public String visit(PrintStmt n, String argu) {
        String expr = n.f1.accept(this, null);
        if (expr != null && expr.matches("^-?\\d+$"))
            out.println("    li $a0, " + expr);
        else if (expr != null && expr.matches("(a[0-3]|t[0-9]|s[0-7]|v[01])"))
            out.println("    move $a0, " + fmtRegName(expr));
        else if (expr != null)
            out.println("    la $a0, " + expr);
        else
            out.println("    li $a0, 0");
        
        out.println("    li $v0, 1");
        out.println("    syscall");
        out.println("    li $a0, 10");     
        out.println("    li $v0, 11");     
        out.println("    syscall");

        return null;
    }

    @Override
    public String visit(ALoadStmt n, String argu) {
        String dest = getReg(n.f1);
        int idx = getSpilledIndex(n.f2);
        int offset = getSpilledArgOffset(argu, idx);
        out.println("    lw " + fmtRegName(dest) + ", " + offset + "($sp)");
        return null;
    }

    @Override
    public String visit(AStoreStmt n, String argu) {
        int idx = getSpilledIndex(n.f1);
        String src = getReg(n.f2);
        int offset = getSpilledArgOffset(argu, idx);
        out.println("    sw " + fmtRegName(src) + ", " + offset + "($sp)");
        return null;
    }

    @Override
    public String visit(PassArgStmt n, String argu) {
        int passIndex = Integer.parseInt(n.f1.f0.toString());
        String src = getReg(n.f2);
        int offset = (passIndex - 1) * 4;
        
        out.println("    sw " + fmtRegName(src) + ", " + offset + "($sp)");
        return null;
    }

    @Override
    public String visit(CallStmt n, String argu) {
        String targ = n.f1.accept(this, null);
        if (targ != null && targ.matches("(a[0-3]|t[0-9]|s[0-7]|v[01])")) {
            out.println("    jalr " + fmtRegName(targ));
        } else {
            out.println("    jal " + targ);
        }
        return null;
    }

    @Override
    public String visit(Exp n, String argu) {
        return n.f0.accept(this, argu);
    }

    @Override
    public String visit(HAllocate n, String argu) {
        String sizeExpr = n.f1.accept(this, null);
        if (sizeExpr != null && sizeExpr.matches("^-?\\d+$"))
            out.println("    li $a0, " + sizeExpr);
        else if (sizeExpr != null && sizeExpr.matches("(a[0-3]|t[0-9]|s[0-7]|v[01])"))
            out.println("    move $a0, " + fmtRegName(sizeExpr));
        else
            out.println("    la $a0, " + sizeExpr);
        out.println("    li $v0, 9");
        out.println("    syscall");
        if (argu != null)
            out.println("    move " + fmtRegName(argu) + ", $v0");
        return "v0";
    }

    @Override
    public String visit(BinOp n, String argu) {
        String op = ((NodeToken) n.f0.f0.choice).toString();
        String left = n.f1.accept(this, null);
        String right = n.f2.accept(this, null);

        String leftReg = fmtRegName(left);
        String rightReg;

        if (right.matches("^-?\\d+$")) {
            out.println("    li $v1, " + right);
            rightReg = "$v1";
        } else if (right.matches("(a[0-3]|t[0-9]|s[0-7]|v[01])")) {
            rightReg = fmtRegName(right);
        } else {
            out.println("    la $v1, " + right);
            rightReg = "$v1";
        }

        String dest = fmtRegName(argu);

        switch (op) {
            case "PLUS":
                out.println("    add " + dest + ", " + leftReg + ", " + rightReg);
                break;
            case "MINUS":
                out.println("    sub " + dest + ", " + leftReg + ", " + rightReg);
                break;
            case "TIMES":
                out.println("    mult " + leftReg + ", " + rightReg);
                out.println("    mflo " + dest);
                break;
            case "DIV":
                out.println("    div " + leftReg + ", " + rightReg);
                out.println("    mflo " + dest);
                break;
            case "LE":
                out.println("    slt $v1, " + rightReg + ", " + leftReg);
                out.println("    xori " + dest + ", $v1, 1");
                break;
            case "NE":
                out.println("    xor $v1, " + leftReg + ", " + rightReg);
                out.println("    sltu " + dest + ", $zero, $v1");
                break;
            default:
                out.println("    # unsupported operator: " + op);
        }
        return null;
    }


    @Override
    public String visit(Operator n, String argu) {
        return n.f0.toString();
    }

    @Override
    public String visit(SpilledArg n, String argu) {
        return n.f1.f0.toString();
    }

    @Override
    public String visit(SimpleExp n, String argu) {
        Node choice = n.f0.choice;
        String token = null;

        if (choice instanceof Reg) {
            token = getReg((Reg) choice);
            if (argu != null)
                out.println("    move " + fmtRegName(argu) + ", " + fmtRegName(token));
        } else if (choice instanceof IntegerLiteral) {
            token = getInt((IntegerLiteral) choice);
            if (argu != null)
                out.println("    li " + fmtRegName(argu) + ", " + token);
        } else if (choice instanceof Label) {
            token = getLabel((Label) choice);
            if (argu != null)
                out.println("    la " + fmtRegName(argu) + ", " + token);
        } else {
            token = choice.toString();
            if (argu != null)
                out.println("    la " + fmtRegName(argu) + ", " + token);
        }
        return token;
    }

    @Override
    public String visit(Reg n, String argu) {
        return getReg(n);
    }

    @Override
    public String visit(IntegerLiteral n, String argu) {
        return getInt(n);
    }

    @Override
    public String visit(Label n, String argu) {
        String label = getLabel(n);
        out.println(label + ":");
        return label;
    }

    @Override
    public String visit(SpillInfo n, String argu) {
        return null;
    }

    @Override
    public String visit(SpillStatus n, String argu) {
        return null;
    }
}