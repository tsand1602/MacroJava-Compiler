import syntaxtree.*;
import visitor.GJDepthFirst;
import java.util.*;
import java.util.List;

public class IRGeneratorVisitor extends GJDepthFirst<IRGeneratorVisitor.IRInfo, String> {

    public static class IRInfo {
        public String code, result, type;
        public IRInfo(String code, String result, String type) { this.code = code; this.result = result; this.type = type; }
    }

    private final SymbolTableVisitor.SymbolTable st;
    private final Map<Node, SymbolTableVisitor.ClassInfo> lambdaClassMap; 
    private String currClass, currMethod;
    private int tempCount = 0, labelCounter = 0;
    private Map<String, Integer> varToTempMap = new HashMap<>();
    private Map<String, String> vTablePtrs = new HashMap<>();

    private static final int GLOBAL_VTABLE_BASE = 0x10010000;
    private Map<String, Integer> globalVTableAddr = new HashMap<>();

    private int lambdaCounter = 0;

    public IRGeneratorVisitor(SymbolTableVisitor.SymbolTable st, Map<Node, SymbolTableVisitor.ClassInfo> lambdaClassMap) {
        this.st = st;
        this.lambdaClassMap = (lambdaClassMap == null) ? new HashMap<>() : lambdaClassMap;
    }

    public IRGeneratorVisitor(SymbolTableVisitor.SymbolTable st) { this(st, null); }

    private String newTemp() { return "TEMP " + tempCount++; }
    private String newLabel() { return "L" + labelCounter++; }

    private String generateVTablesCode() {
        StringBuilder VTCode = new StringBuilder();
        for (String className : st.classes.keySet()) {
            SymbolTableVisitor.ClassInfo ci = st.getClass(className);
            if (ci.methods.containsKey("main")) continue;

            String vTablePtr = newTemp();
            vTablePtrs.put(className, vTablePtr);
            int VTSize = ci.VTSize;
            VTCode.append("MOVE ").append(vTablePtr).append(" HALLOCATE ").append(VTSize).append("\n");

            for (Map.Entry<String, Integer> entry : ci.methodOffsets.entrySet()) {
                String methodName = entry.getKey();
                int offset = entry.getValue();
                String methodOwner = findMethodOwner(className, methodName);
                String methodLabel = methodOwner + "_" + methodName;
                String labelTemp = newTemp();
                VTCode.append("MOVE ").append(labelTemp).append(" ").append(methodLabel).append("\n");
                VTCode.append("HSTORE ").append(vTablePtr).append(" ").append(offset).append(" ").append(labelTemp).append("\n");
            }

            int globalAddr = GLOBAL_VTABLE_BASE + 4 * globalVTableAddr.size();
            globalVTableAddr.put(className, globalAddr);
            String addrTemp = newTemp();
            VTCode.append("MOVE ").append(addrTemp).append(" ").append(globalAddr).append("\n");
            VTCode.append("HSTORE ").append(addrTemp).append(" 0 ").append(vTablePtr).append("\n");
        }
        return VTCode.toString();
    }

    private String generateLambdaMethods() {
        if (lambdaClassMap == null) return "";
        StringBuilder lambdaCode = new StringBuilder();
        for (SymbolTableVisitor.ClassInfo ci : st.classes.values()) {
            if (ci.name != null && ci.name.startsWith("Lambda$")) {
                currClass = ci.name;
                SymbolTableVisitor.MethodInfo applyMethod = ci.methods.get("apply");
                if (applyMethod == null) continue;
                currMethod = applyMethod.name;

                tempCount = 0;
                varToTempMap.clear();
                varToTempMap.put("this", tempCount++); 
                for (String paramName : applyMethod.params.keySet()) varToTempMap.put(paramName, tempCount++);
                if (tempCount < 30) tempCount = 30;

                int numArgs = applyMethod.params.size() + 1;
                String methodLabel = currClass + "_" + currMethod;

                lambdaCode.append("\n").append(methodLabel).append(" [").append(numArgs).append("]\n").append("BEGIN\n");

                IRInfo returnExp = null;
                if (applyMethod.body != null) returnExp = applyMethod.body.accept(this, null);
                if (returnExp != null && returnExp.code != null) lambdaCode.append(returnExp.code);
                String retRes = (returnExp != null && returnExp.result != null) ? returnExp.result : "TEMP 0";
                lambdaCode.append("RETURN ").append(retRes).append("\n").append("END\n");

                currClass = null;
                currMethod = null;
            }
        }
        return lambdaCode.toString();
    }

    private String findMethodOwner(String className, String methodName) {
        SymbolTableVisitor.ClassInfo ci = st.getClass(className);
        while (ci != null) {
            if (ci.methods.containsKey(methodName)) return ci.name;
            ci = (ci.parentName != null) ? st.getClass(ci.parentName) : null;
        }
        return null;
    }

    private boolean isMethodOverridden(String baseClassName, String methodName) {
        for (SymbolTableVisitor.ClassInfo potentialSubclass : st.classes.values()) {
            boolean isSubclass = false;
            SymbolTableVisitor.ClassInfo temp = potentialSubclass;
            while (temp != null && temp.parentName != null) {
                if (temp.parentName.equals(baseClassName)) { isSubclass = true; break; }
                temp = st.getClass(temp.parentName);
            }
            if (isSubclass && potentialSubclass.methods.containsKey(methodName)) return true;
        }
        return false;
    }

    private String findFieldType(String className, String fieldName) {
        SymbolTableVisitor.ClassInfo ci = st.getClass(className);
        while (ci != null) {
            if (ci.fields.containsKey(fieldName)) return ci.fields.get(fieldName);
            ci = (ci.parentName != null) ? st.getClass(ci.parentName) : null;
        }
        return null;
    }

    private SymbolTableVisitor.ClassInfo synthesizeLambdaForArgument(LambdaExpression n, String targetType) {
        if (lambdaClassMap.containsKey(n)) return lambdaClassMap.get(n);

        SymbolTableVisitor.ClassInfo parent = st.getClass(targetType);
        if (parent == null) {
            return null;
        }

        String lambdaName = "Lambda$" + (lambdaCounter++);
        st.addClass(lambdaName, targetType);
        SymbolTableVisitor.ClassInfo lambdaCi = st.getClass(lambdaName);

        Identifier paramId = (Identifier) n.f1;
        String paramName = paramId.f0.tokenImage;

        SymbolTableVisitor.MethodInfo parentApply = parent.methods.get("apply");
        SymbolTableVisitor.MethodInfo applyMethod = new SymbolTableVisitor.MethodInfo();
        applyMethod.name = "apply";
        if (parentApply != null) {
            applyMethod.returnType = parentApply.returnType;
            if (parentApply.params.size() == 1) {
                String parentParamType = parentApply.params.values().iterator().next();
                applyMethod.params.put(paramName, parentParamType);
            } else {
                applyMethod.params.put(paramName, "int");
            }
        } else {
            applyMethod.returnType = "int";
            applyMethod.params.put(paramName, "int");
        }

        applyMethod.body = n.f4;
        lambdaCi.methods.put("apply", applyMethod);

        lambdaCi.fieldOffsets.putAll(parent.fieldOffsets);
        lambdaCi.methodOffsets.putAll(parent.methodOffsets);

        int fieldOffset = parent.objectSize;
        lambdaCi.objectSize = fieldOffset;

        lambdaCi.VTSize = lambdaCi.methodOffsets.size() * 4;

        lambdaClassMap.put(n, lambdaCi);

        return lambdaCi;
    }

    @Override
    public IRInfo visit(Goal n, String argu) {
        StringBuilder finalCode = new StringBuilder();
        finalCode.append(n.f1.accept(this, null).code);
        if (n.f2.present()) {
            for (Node node : n.f2.nodes) {
                finalCode.append(node.accept(this, null).code);
            }
        }
        finalCode.append(generateLambdaMethods());
        return new IRInfo(finalCode.toString(), null, null);
    }

    @Override
    public IRInfo visit(MainClass n, String argu) {
        currClass = n.f1.f0.tokenImage;
        currMethod = "main";

        StringBuilder code = new StringBuilder("MAIN\n");
        code.append(generateVTablesCode());

        IRInfo stmtInfo = n.f14.accept(this, null);
        if (stmtInfo != null && stmtInfo.code != null) code.append(stmtInfo.code);
        code.append("END\n");

        currClass = null;
        currMethod = null;
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(TypeDeclaration n, String argu) { return n.f0.accept(this, argu); }

    @Override 
    public IRInfo visit(ClassDeclaration n, String argu) {
        currClass = n.f1.f0.tokenImage;
        StringBuilder code = new StringBuilder();
        if (n.f4.present()) for (Node node : n.f4.nodes) code.append(node.accept(this, null).code);
        currClass = null;
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(ClassExtendsDeclaration n, String argu) {
        currClass = n.f1.f0.tokenImage;
        StringBuilder code = new StringBuilder();
        if (n.f6.present()) for (Node node : n.f6.nodes) code.append(node.accept(this, null).code);
        currClass = null;
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(MethodDeclaration n, String argu) {
        tempCount = 0;
        varToTempMap.clear();
        currMethod = n.f2.f0.tokenImage;

        SymbolTableVisitor.MethodInfo method = st.getClass(currClass).methods.get(currMethod);

        varToTempMap.put("this", tempCount++);
        for (String paramName : method.params.keySet()) varToTempMap.put(paramName, tempCount++);

        if (tempCount < 30) tempCount = 30;

        if (n.f7.present()) for (Node varNode : n.f7.nodes) varToTempMap.put(((VarDeclaration) varNode).f1.f0.tokenImage, tempCount++);

        int numArgs = method.params.size() + 1;
        String methodLabel = currClass + "_" + currMethod;
        StringBuilder code = new StringBuilder("\n").append(methodLabel).append(" [").append(numArgs).append("]\n").append("BEGIN\n");

        if (n.f8.present()) for (Node stmtNode : n.f8.nodes) code.append(stmtNode.accept(this, null).code);

        IRInfo returnExp = n.f10.accept(this, null);
        if (returnExp != null && returnExp.code != null) code.append(returnExp.code);
        String retRes = (returnExp != null && returnExp.result != null) ? returnExp.result : "0";
        code.append("RETURN ").append(retRes).append("\n").append("END\n");

        currMethod = null;
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(AllocationExpression n, String argu) {
        String className = n.f1.f0.tokenImage;
        SymbolTableVisitor.ClassInfo ci = st.getClass(className);
        int objectSize = ci.objectSize;
        String objPtr = newTemp();
        StringBuilder code = new StringBuilder();
        code.append("MOVE ").append(objPtr).append(" HALLOCATE ").append(objectSize).append("\n");

        Integer globalAddr = globalVTableAddr.get(className);
        String addrTemp = newTemp();
        String vTableTemp = newTemp();
        code.append("MOVE ").append(addrTemp).append(" ").append(globalAddr).append("\n");
        code.append("HLOAD ").append(vTableTemp).append(" ").append(addrTemp).append(" 0\n");
        code.append("HSTORE ").append(objPtr).append(" 0 ").append(vTableTemp).append("\n");
        String zeroTemp = newTemp();
        code.append("MOVE ").append(zeroTemp).append(" 0\n");
        for (int offset = 4; offset < objectSize; offset += 4) code.append("HSTORE ").append(objPtr).append(" ").append(offset).append(" ").append(zeroTemp).append("\n");
        return new IRInfo(code.toString(), objPtr, className);
    }

    @Override 
    public IRInfo visit(ArrayAllocationExpression n, String argu) {
        IRInfo size = n.f3.accept(this, null);
        StringBuilder code = new StringBuilder(size.code);
        String tempForBytes = newTemp();
        String totalBytes = newTemp();
        code.append("MOVE ").append(tempForBytes).append(" TIMES ").append(size.result).append(" 4\n");
        code.append("MOVE ").append(totalBytes).append(" PLUS ").append(tempForBytes).append(" 4\n");
        String arrPtr = newTemp();
        code.append("MOVE ").append(arrPtr).append(" HALLOCATE ").append(totalBytes).append("\n");
        code.append("HSTORE ").append(arrPtr).append(" 0 ").append(size.result).append("\n");
        String counter = newTemp(), zeroTemp = newTemp(), startLabel = newLabel(), endLabel = newLabel();
        code.append("MOVE ").append(counter).append(" 0\n").append("MOVE ").append(zeroTemp).append(" 0\n");
        code.append(startLabel).append(" NOOP\n");
        String condTemp = newTemp();
        code.append("MOVE ").append(condTemp).append(" LE ").append(counter).append(" MINUS ").append(size.result).append(" 1\n");
        code.append("CJUMP ").append(condTemp).append(" ").append(endLabel).append("\n");
        String offsetBytes = newTemp(), finalAddr = newTemp(), elementBaseAddr = newTemp();
        code.append("MOVE ").append(offsetBytes).append(" TIMES ").append(counter).append(" 4\n");
        code.append("MOVE ").append(elementBaseAddr).append(" PLUS ").append(arrPtr).append(" ").append(offsetBytes).append("\n");
        code.append("MOVE ").append(finalAddr).append(" PLUS ").append(elementBaseAddr).append(" 4\n");
        code.append("HSTORE ").append(finalAddr).append(" 0 ").append(zeroTemp).append("\n");
        code.append("MOVE ").append(counter).append(" PLUS ").append(counter).append(" 1\n");
        code.append("JUMP ").append(startLabel).append("\n").append(endLabel).append(" NOOP\n");
        return new IRInfo(code.toString(), arrPtr, "int[]");
    }

    @Override 
    public IRInfo visit(ThisExpression n, String argu) { 
        return new IRInfo("", "TEMP 0", this.currClass); 
    }

    @Override 
    public IRInfo visit(IntegerLiteral n, String argu) {
        String temp = newTemp();
        String code = "MOVE " + temp + " " + n.f0.tokenImage + "\n";
        return new IRInfo(code, temp, "int");
    }

    @Override 
    public IRInfo visit(TrueLiteral n, String argu) {
        String temp = newTemp();
        String code = "MOVE " + temp + " 1\n";
        return new IRInfo(code, temp, "boolean");
    }

    @Override 
    public IRInfo visit(FalseLiteral n, String argu) {
        String temp = newTemp();
        String code = "MOVE " + temp + " 0\n";
        return new IRInfo(code, temp, "boolean");
    }

    @Override 
    public IRInfo visit(Statement n, String argu) { 
        return n.f0.accept(this, argu); 
    }

    @Override 
    public IRInfo visit(Block n, String argu) {
        StringBuilder code = new StringBuilder();
        if (n.f1.present()) for (Node stmtNode : n.f1.nodes) code.append(stmtNode.accept(this, null).code);
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(AssignmentStatement n, String argu) {
        String id = n.f0.f0.tokenImage;
        IRInfo rhs = n.f2.accept(this, null);
        StringBuilder code = new StringBuilder(rhs.code);
        if (varToTempMap.containsKey(id)) {
            code.append("MOVE TEMP ").append(varToTempMap.get(id)).append(" ").append(rhs.result).append("\n");
        } else {
            int offset = st.getClass(currClass).fieldOffsets.get(id);
            code.append("HSTORE TEMP 0 ").append(offset).append(" ").append(rhs.result).append("\n");
        }
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(ArrayAssignmentStatement n, String argu) {
        IRInfo arr = n.f0.accept(this, null), index = n.f2.accept(this, null), rhs = n.f5.accept(this, null);
        StringBuilder code = new StringBuilder(arr.code).append(index.code).append(rhs.code);
        String indexInBytes = newTemp(), offset = newTemp(), targetAddr = newTemp();
        code.append("MOVE ").append(indexInBytes).append(" TIMES ").append(index.result).append(" 4\n");
        code.append("MOVE ").append(offset).append(" PLUS ").append(indexInBytes).append(" 4\n");
        code.append("MOVE ").append(targetAddr).append(" PLUS ").append(arr.result).append(" ").append(offset).append("\n");
        code.append("HSTORE ").append(targetAddr).append(" 0 ").append(rhs.result).append("\n");
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(IfStatement n, String argu) { 
        return n.f0.accept(this, argu); 
    }

    @Override 
    public IRInfo visit(IfthenStatement n, String argu) {
        IRInfo cond = n.f2.accept(this, null), thenStmt = n.f4.accept(this, null);
        String endLabel = newLabel();
        StringBuilder code = new StringBuilder(cond.code);
        code.append("CJUMP ").append(cond.result).append(" ").append(endLabel).append("\n");
        code.append(thenStmt.code);
        code.append(endLabel).append(" NOOP\n");
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(IfthenElseStatement n, String argu) {
        IRInfo cond = n.f2.accept(this, null), thenStmt = n.f4.accept(this, null), elseStmt = n.f6.accept(this, null);
        String elseLabel = newLabel(), endLabel = newLabel();
        StringBuilder code = new StringBuilder(cond.code);
        code.append("CJUMP ").append(cond.result).append(" ").append(elseLabel).append("\n");
        code.append(thenStmt.code).append("JUMP ").append(endLabel).append("\n");
        code.append(elseLabel).append(" NOOP\n").append(elseStmt.code);
        code.append(endLabel).append(" NOOP\n");
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(WhileStatement n, String argu) {
        String startLabel = newLabel(), endLabel = newLabel();
        IRInfo cond = n.f2.accept(this, null), body = n.f4.accept(this, null);
        StringBuilder code = new StringBuilder();
        code.append(startLabel).append(" NOOP\n");
        code.append(cond.code);
        code.append("CJUMP ").append(cond.result).append(" ").append(endLabel).append("\n");
        code.append(body.code);
        code.append("JUMP ").append(startLabel).append("\n");
        code.append(endLabel).append(" NOOP\n");
        return new IRInfo(code.toString(), null, null);
    }

    @Override 
    public IRInfo visit(PrintStatement n, String argu) {
        IRInfo exp = n.f2.accept(this, null);
        return new IRInfo(exp.code + "PRINT " + exp.result + "\n", null, null);
    }

    @Override 
    public IRInfo visit(Expression n, String argu) { 
        return n.f0.accept(this, argu); 
    }

    private IRInfo visitBinaryOp(Node n0, Node n2, String op, String type) {
        IRInfo left = n0.accept(this, null), right = n2.accept(this, null);
        String resultTemp = newTemp();
        return new IRInfo(left.code + right.code + "MOVE " + resultTemp + " " + op + " " + left.result + " " + right.result + "\n", resultTemp, type);
    }

    @Override 
    public IRInfo visit(AddExpression n, String argu) { 
        return visitBinaryOp(n.f0, n.f2, "PLUS", "int"); 
    }

    @Override 
    public IRInfo visit(MinusExpression n, String argu) { 
        return visitBinaryOp(n.f0, n.f2, "MINUS", "int"); 
    }

    @Override 
    public IRInfo visit(TimesExpression n, String argu) { 
        return visitBinaryOp(n.f0, n.f2, "TIMES", "int"); 
    }

    @Override 
    public IRInfo visit(DivExpression n, String argu) { 
        return visitBinaryOp(n.f0, n.f2, "DIV", "int"); 
    }

    @Override 
    public IRInfo visit(CompareExpression n, String argu) { 
        return visitBinaryOp(n.f0, n.f2, "LE", "boolean"); 
    }

    @Override 
    public IRInfo visit(neqExpression n, String argu) { 
        return visitBinaryOp(n.f0, n.f2, "NE", "boolean"); 
    }

    @Override 
    public IRInfo visit(AndExpression n, String argu) {
        String falseLabel = newLabel(), endLabel = newLabel(), resultTemp = newTemp();
        IRInfo left = n.f0.accept(this, null);
        StringBuilder code = new StringBuilder(left.code);
        code.append("CJUMP ").append(left.result).append(" ").append(falseLabel).append("\n");
        IRInfo right = n.f2.accept(this, null);
        code.append(right.code).append("MOVE ").append(resultTemp).append(" ").append(right.result).append("\n");
        code.append("JUMP ").append(endLabel).append("\n");
        code.append(falseLabel).append(" NOOP\n").append("MOVE ").append(resultTemp).append(" 0\n");
        code.append(endLabel).append(" NOOP\n");
        return new IRInfo(code.toString(), resultTemp, "boolean");
    }

    @Override
    public IRInfo visit(OrExpression n, String argu) {
        String evalRightLabel = newLabel();
        String endLabel = newLabel();
        String resultTemp = newTemp();

        IRInfo left = n.f0.accept(this, null);
        StringBuilder code = new StringBuilder(left.code);

        code.append("CJUMP ").append(left.result).append(" ").append(evalRightLabel).append("\n");

        code.append("MOVE ").append(resultTemp).append(" 1\n");
        code.append("JUMP ").append(endLabel).append("\n");

        code.append(evalRightLabel).append(" NOOP\n");
        IRInfo right = n.f2.accept(this, null);
        code.append(right.code);
        code.append("MOVE ").append(resultTemp).append(" ").append(right.result).append("\n");

        code.append(endLabel).append(" NOOP\n");

        return new IRInfo(code.toString(), resultTemp, "boolean");
    }

    @Override 
    public IRInfo visit(ArrayLookup n, String argu) {
        IRInfo arr = n.f0.accept(this, null), index = n.f2.accept(this, null);
        String offset = newTemp(), targetAddr = newTemp(), resultTemp = newTemp();
        return new IRInfo(arr.code + index.code + "MOVE " + offset + " PLUS 4 TIMES " + index.result + " 4\n" + "MOVE " + targetAddr + " PLUS " + arr.result + " " + offset + "\n" + "HLOAD " + resultTemp + " " + targetAddr + " 0\n", resultTemp, "int");
    }

    @Override 
    public IRInfo visit(ArrayLength n, String argu) {
        IRInfo arr = n.f0.accept(this, null);
        String resultTemp = newTemp();
        return new IRInfo(arr.code + "HLOAD " + resultTemp + " " + arr.result + " 0\n", resultTemp, "int");
    }

    @Override 
    public IRInfo visit(PrimaryExpression n, String argu) { return n.f0.accept(this, argu); }

    @Override 
    public IRInfo visit(NotExpression n, String argu) {
        IRInfo exp = n.f1.accept(this, null);
        String resultTemp = newTemp();
        return new IRInfo(exp.code + "MOVE " + resultTemp + " MINUS 1 " + exp.result + "\n", resultTemp, "boolean");
    }

    @Override 
    public IRInfo visit(BracketExpression n, String argu) { 
        return n.f1.accept(this, argu); 
    }

    @Override
    public IRInfo visit(LambdaExpression n, String argu) {
        if (lambdaClassMap == null) {
            return new IRInfo("", "0", "lambda");
        }
        SymbolTableVisitor.ClassInfo lambdaClass = lambdaClassMap.get(n);
        if (lambdaClass == null) {
            return new IRInfo("", "0", "lambda");
        }
        StringBuilder code = new StringBuilder();
        String objPtr = newTemp();
        code.append("MOVE ").append(objPtr).append(" HALLOCATE ").append(lambdaClass.objectSize).append("\n");

        String vTableTemp = vTablePtrs.get(lambdaClass.name);
        if (vTableTemp == null) {
            String vtPtr = newTemp();
            vTablePtrs.put(lambdaClass.name, vtPtr);
            code.append("MOVE ").append(vtPtr).append(" HALLOCATE ").append(lambdaClass.VTSize).append("\n");
            for (Map.Entry<String, Integer> e : lambdaClass.methodOffsets.entrySet()) {
                String methodName = e.getKey();
                int offset = e.getValue();
                String methodOwner = findMethodOwner(lambdaClass.name, methodName);
                String methodLabel = (methodOwner != null ? methodOwner : lambdaClass.name) + "_" + methodName;
                String labelTemp = newTemp();
                code.append("MOVE ").append(labelTemp).append(" ").append(methodLabel).append("\n");
                code.append("HSTORE ").append(vtPtr).append(" ").append(offset).append(" ").append(labelTemp).append("\n");
            }
            vTableTemp = vtPtr;
        }

        code.append("HSTORE ").append(objPtr).append(" 0 ").append(vTableTemp).append("\n");
        for (String fieldName : lambdaClass.fields.keySet()) {
            int fieldOffset = lambdaClass.fieldOffsets.get(fieldName);
            String sourceTemp;
            if (fieldName.equals("this_")) {
                sourceTemp = "TEMP 0";
            } else if (varToTempMap.containsKey(fieldName)) {
                sourceTemp = "TEMP " + varToTempMap.get(fieldName);
            } else {
                if (currClass != null && st.getClass(currClass).fieldOffsets.containsKey(fieldName)) {
                    int sourceOffset = st.getClass(currClass).fieldOffsets.get(fieldName);
                    sourceTemp = newTemp();
                    code.append("HLOAD ").append(sourceTemp).append(" TEMP 0 ").append(sourceOffset).append("\n");
                } else {
                    sourceTemp = "0";
                }
            }
            code.append("HSTORE ").append(objPtr).append(" ").append(fieldOffset).append(" ").append(sourceTemp).append("\n");
        }
        return new IRInfo(code.toString(), objPtr, lambdaClass.name);
    }

    private String materializeIfBlock(StringBuilder codeBuilder, String expr) {
        if (expr == null) return "0";
        String trimmed = expr.trim();
        if (trimmed.startsWith("BEGIN")) {
            String t = newTemp();
            codeBuilder.append("MOVE ").append(t).append("\n");
            codeBuilder.append(trimmed).append("\n");
            return t;
        } else {
            return expr;
        }
    }

    @Override
    public IRInfo visit(MessageSend n, String argu) {
        IRInfo obj = n.f0.accept(this, null);
        String methodName = n.f2.f0.tokenImage;
        String objStaticType = obj.type;

        if (objStaticType == null || "lambda".equals(objStaticType) || st.getClass(objStaticType) == null) {
            return new IRInfo(obj.code, "0", "int");
        }

        StringBuilder code = new StringBuilder();
        if (obj.code != null) code.append(obj.code);

        String callObjTemp = materializeIfBlock(code, obj.result);

        String ownerClassForSig = findMethodOwner(objStaticType, methodName);
        SymbolTableVisitor.MethodInfo calleeSig = null;
        if (ownerClassForSig != null) calleeSig = st.getClass(ownerClassForSig).methods.get(methodName);

        List<String> paramTypes = new ArrayList<>();
        if (calleeSig != null) {
            for (String t : calleeSig.params.values()) paramTypes.add(t);
        }

        List<String> argResults = new ArrayList<>();
        if (n.f4.present()) {
            ExpressionList el = (ExpressionList) n.f4.node;
            List<Node> argNodes = new ArrayList<>();
            argNodes.add(el.f0);
            for (Node node : el.f1.nodes) argNodes.add(((ExpressionRest) node).f1);

            for (int i = 0; i < argNodes.size(); ++i) {
                Node argNode = argNodes.get(i);
                if (argNode instanceof LambdaExpression && i < paramTypes.size()) {
                    String targetType = paramTypes.get(i);
                    LambdaExpression lex = (LambdaExpression) argNode;
                    if (!lambdaClassMap.containsKey(lex)) {
                        synthesizeLambdaForArgument(lex, targetType);
                    }
                }

                IRInfo argInfo = argNode.accept(this, null);
                if (argInfo != null) {
                    if (argInfo.code != null) code.append(argInfo.code);
                    String safeArg = materializeIfBlock(code, argInfo.result);
                    argResults.add(safeArg);
                }
            }
        }

        String resultTemp = newTemp();

        if (isMethodOverridden(objStaticType, methodName)) {
            SymbolTableVisitor.ClassInfo ci = st.getClass(objStaticType);
            Integer methodOffset = (ci != null) ? ci.methodOffsets.get(methodName) : null;
            if (methodOffset == null) {
                String owner = findMethodOwner(objStaticType, methodName);
                String directLabel = (owner != null) ? owner + "_" + methodName : methodName;
                code.append("MOVE ").append(resultTemp).append(" CALL ").append(directLabel).append("( ").append(callObjTemp);
            } else {
                String vTablePtrTemp = newTemp();
                code.append("HLOAD ").append(vTablePtrTemp).append(" ").append(callObjTemp).append(" 0\n");
                String methodAddrTemp = newTemp();
                code.append("HLOAD ").append(methodAddrTemp).append(" ").append(vTablePtrTemp).append(" ").append(methodOffset).append("\n");
                code.append("MOVE ").append(resultTemp).append(" CALL ").append(methodAddrTemp).append("( ").append(callObjTemp);
            }
        } else {
            String ownerClass = findMethodOwner(objStaticType, methodName);
            String directLabel = ownerClass + "_" + methodName;
            code.append("MOVE ").append(resultTemp).append(" CALL ").append(directLabel).append("( ").append(callObjTemp);
        }

        for (String arg : argResults) code.append(" ").append(arg);
        code.append(" )\n");

        String returnType = "int"; 
        SymbolTableVisitor.ClassInfo clsInfo = st.getClass(objStaticType);
        if (clsInfo != null) {
            SymbolTableVisitor.MethodInfo mi = clsInfo.methods.get(methodName);
            if (mi != null) returnType = mi.returnType;
        }

        return new IRInfo(code.toString(), resultTemp, returnType);
    }

    @Override
    public IRInfo visit(Identifier n, String argu) {
        String id = n.f0.tokenImage;
        String varType = null;

        if (varToTempMap.containsKey(id)) {
            if (currClass != null && currMethod != null) {
                SymbolTableVisitor.MethodInfo method = st.getClass(currClass).methods.get(currMethod);
                if (method != null) {
                    if (method.locals.containsKey(id)) varType = method.locals.get(id);
                    if (method.params.containsKey(id)) varType = method.params.get(id);
                }
            }
            return new IRInfo("", "TEMP " + varToTempMap.get(id), varType);
        }

        if (currClass != null) {
            SymbolTableVisitor.ClassInfo cls = st.getClass(currClass);
            if (cls != null && cls.fieldOffsets.containsKey(id)) {
                int offset = cls.fieldOffsets.get(id);
                varType = findFieldType(currClass, id);
                String temp = newTemp();
                String code = "HLOAD " + temp + " TEMP 0 " + offset + "\n";
                return new IRInfo(code, temp, varType);
            }
        }
        return new IRInfo("", id, id);
    }
}
