import syntaxtree.*;
import visitor.GJDepthFirst;
import java.util.*;

public class MiniToMicroTranslator extends GJDepthFirst<String, Void> {
    private final StringBuilder out = new StringBuilder();
    private int tempI = 200;

    private void append(String line) {
        out.append(line).append("\n");
    }

    private String creatingTemp() {
        return "TEMP " + (tempI++);
    }

    public String output() {
        return out.toString();
    }

    @Override
    public String visit(Goal n, Void argu) {
        append("MAIN");
        n.f1.accept(this, argu);
        append("END");

        if (n.f3.present()) {
            for (Node e : n.f3.nodes) {
                e.accept(this, argu);
            }
        }
        return null;
    }

    @Override
    public String visit(Procedure n, Void argu) {
        String labelName = n.f0.f0.toString();
        String arity = n.f2.f0.toString();

        append(labelName + " [" + arity + "]");
        append("BEGIN");

        n.f4.f1.accept(this, argu);

        String ReturningTemp = n.f4.f3.accept(this, argu);

        append("RETURN " + ReturningTemp);
        append("END");

        return null;
    }

    @Override
    public String visit(StmtList n, Void argu) {
        if (n.f0.present()) {
            for (Node node : n.f0.nodes) {
                NodeSequence sequence = (NodeSequence) node;
                NodeOptional labelOpt = (NodeOptional) sequence.elementAt(0);
                if (labelOpt.present()) {
                    Label labelName = (Label) labelOpt.node;
                    append(labelName.f0.toString());
                }
                Node statement = sequence.elementAt(1);
                statement.accept(this, argu);
            }
        }
        return null;
    }

    @Override
    public String visit(Stmt n, Void argu) {
        return n.f0.accept(this, argu);
    }

    @Override
    public String visit(NoOpStmt n, Void argu) {
        append("NOOP");
        return null;
    }

    @Override
    public String visit(ErrorStmt n, Void argu) {
        append("ERROR");
        return null;
    }

    @Override
    public String visit(CJumpStmt n, Void argu) {
        String condition = n.f1.accept(this, argu);
        String labelName = n.f2.f0.toString();
        append("CJUMP " + condition + " " + labelName);
        return null;
    }

    @Override
    public String visit(JumpStmt n, Void argu) {
        String labelName = n.f1.f0.toString();
        append("JUMP " + labelName);
        return null;
    }

    @Override
    public String visit(HStoreStmt n, Void argu) {
        String addr = n.f1.accept(this, argu);
        String off = n.f2.f0.toString();
        String val = n.f3.accept(this, argu);
        append("HSTORE " + addr + " " + off + " " + val);
        return null;
    }

    @Override
    public String visit(HLoadStmt n, Void argu) {
        String dest = n.f1.accept(this, argu);
        String addr = n.f2.accept(this, argu);
        String off = n.f3.f0.toString();
        append("HLOAD " + dest + " " + addr + " " + off);
        return null;
    }

    @Override
    public String visit(MoveStmt n, Void argu) {
        String dest = n.f1.accept(this, argu);
        Node ChoiceExp = n.f2.f0.choice;
        if (ChoiceExp instanceof BinOp) {
            BinOp op = (BinOp) ChoiceExp;
            String StringOp = op.f0.accept(this, argu);
            String left = op.f1.accept(this, argu);
            String right = op.f2.accept(this, argu);
            append("MOVE " + dest + " " + StringOp + " " + left + " " + right);
        } else if (ChoiceExp instanceof Call) {
            Call call = (Call) ChoiceExp;
            String func = call.f1.accept(this, argu);
            StringBuilder sb = new StringBuilder();
            if (call.f3.present()) {
                for (Node node : call.f3.nodes) {
                    sb.append(" ").append(node.accept(this, argu));
                }
            }
            append("MOVE " + dest + " CALL " + func + " (" + sb.toString().trim() + ")");
        } else if (ChoiceExp instanceof HAllocate) {
            HAllocate halloc = (HAllocate) ChoiceExp;
            String size = halloc.f1.accept(this, argu);
            append("MOVE " + dest + " HALLOCATE " + size);
        } else {
            String src = n.f2.accept(this, argu);
            append("MOVE " + dest + " " + src);
        }
        return null;
    }

    @Override
    public String visit(PrintStmt n, Void argu) {
        String statement = n.f1.accept(this, argu);
        append("PRINT " + statement);
        return null;
    }

    @Override
    public String visit(Exp n, Void argu) {
        return n.f0.accept(this, argu);
    }

    @Override
    public String visit(StmtExp n, Void argu) {
        n.f1.accept(this, argu);
        return n.f3.accept(this, argu);
    }

    @Override
    public String visit(Call n, Void argu) {
        String func = n.f1.accept(this, argu);
        List<String> arguments = new ArrayList<>();
        if (n.f3.present()) {
            for (Node node : n.f3.nodes) {
                arguments.add(node.accept(this, argu));
            }
        }
        String res = creatingTemp();
        append("MOVE " + res + " CALL " + func + " (" + String.join(" ", arguments) + ")");
        return res;
    }

    @Override
    public String visit(HAllocate n, Void argu) {
        String size = n.f1.accept(this, argu);
        String res = creatingTemp();
        append("MOVE " + res + " HALLOCATE " + size);
        return res;
    }

    @Override
    public String visit(BinOp n, Void argu) {
        String op = n.f0.accept(this, argu);
        String left = n.f1.accept(this, argu);
        String right = n.f2.accept(this, argu);
        String res = creatingTemp();
        append("MOVE " + res + " " + op + " " + left + " " + right);
        return res;
    }

    @Override
    public String visit(Operator n, Void argu) {
        return ((NodeToken) n.f0.choice).tokenImage;
    }

    @Override
    public String visit(Temp n, Void argu) {
        return "TEMP " + n.f1.f0.toString();
    }

    @Override
    public String visit(IntegerLiteral n, Void argu) {
        return n.f0.toString();
    }

    @Override
    public String visit(Label n, Void argu) {
        return n.f0.toString();
    }
}