import syntaxtree.*;
import visitor.GJDepthFirst;
import java.util.*;

public class SymbolTableVisitor extends GJDepthFirst<String, Void> {

    public static class ClassInfo {
        public String name, parentName;
        public Map<String, String> fields = new LinkedHashMap<>();
        public Map<String, MethodInfo> methods = new LinkedHashMap<>();
        public Map<String, Integer> fieldOffsets = new LinkedHashMap<>();
        public Map<String, Integer> methodOffsets = new LinkedHashMap<>();
        public int objectSize;
        public int VTSize;
    }
    public static class MethodInfo {
        public String name, returnType;
        public Map<String, String> params = new LinkedHashMap<>();
        public Map<String, String> locals = new LinkedHashMap<>();
        public Node body; 
    }
    public static class SymbolTable {
        public Map<String, ClassInfo> classes = new LinkedHashMap<>();
        public void addClass(String name, String parent) {
            if (classes.containsKey(name)) return;
            classes.put(name, new ClassInfo());
            classes.get(name).name = name;
            classes.get(name).parentName = parent;
        }
        public ClassInfo getClass(String name) { return classes.get(name); }
    }

    private SymbolTable st = new SymbolTable();
    private ClassInfo currentClass;
    private MethodInfo currentMethod;
    private Map<String, Boolean> offsetsCalculated = new HashMap<>();
    private int lambdaCounter = 0;
    
    public Map<Node, ClassInfo> lambdaClassMap = new HashMap<>();

    public SymbolTable getSymbolTable() {
        for (String className : st.classes.keySet()) {
            calculateClassOffsets(className);
        }
        return st;
    }
    
    private void calculateClassOffsets(String className) {
        if (className == null || offsetsCalculated.getOrDefault(className, false)) return;
        ClassInfo ci = st.getClass(className);
        if (ci == null) return;

        calculateClassOffsets(ci.parentName);

        int fieldOffset = 4;
        int methodOffset = 0;

        if (ci.parentName != null) {
            ClassInfo parent = st.getClass(ci.parentName);
            ci.fieldOffsets.putAll(parent.fieldOffsets);
            ci.methodOffsets.putAll(parent.methodOffsets);
            fieldOffset = parent.objectSize;
            methodOffset = parent.methodOffsets.size() * 4;
        }

        for (String fieldName : ci.fields.keySet()) {
            ci.fieldOffsets.put(fieldName, fieldOffset);
            fieldOffset += 4;
        }

        ci.objectSize = fieldOffset;

        for (String methodName : ci.methods.keySet()) {
            if (!ci.methodOffsets.containsKey(methodName)) {
                ci.methodOffsets.put(methodName, methodOffset);
                methodOffset += 4;
            }
        }
        ci.VTSize = ci.methodOffsets.size() * 4;
        offsetsCalculated.put(className, true);
    }
    
    private class LambdaTypeVisitor extends GJDepthFirst<Void, Set<String>> {
        @Override
        public Void visit(LambdaType n, Set<String> createdBaseClasses) {
            String t1 = n.f2.accept(new TypeVisitor(), null);
            String t2 = n.f4.accept(new TypeVisitor(), null);
            String baseClassName = "Func_" + t1 + "_" + t2;

            if (!createdBaseClasses.contains(baseClassName)) {
                st.addClass(baseClassName, null);
                ClassInfo baseClass = st.getClass(baseClassName);
                
                MethodInfo applyMethod = new MethodInfo();
                applyMethod.name = "apply";
                applyMethod.returnType = t2;
                applyMethod.params.put("param", t1);
                baseClass.methods.put("apply", applyMethod);

                createdBaseClasses.add(baseClassName);
            }
            return null;
        }
    }
    
    private class TypeVisitor extends GJDepthFirst<String, Void> {
        @Override 
        public String visit(Type n, Void argu) { 
            return n.f0.accept(this, argu); 
        }

        @Override 
        public String visit(ArrayType n, Void argu) { 
            return "int[]"; 
        }
        
        @Override 
        public String visit(BooleanType n, Void argu) { 
            return "boolean"; 
        }
        
        @Override 
        public String visit(IntegerType n, Void argu) { 
            return "int"; 
        }
        
        @Override 
        public String visit(Identifier n, Void argu) { 
            return n.f0.tokenImage; 
        }
    }
    
    private class FreeVariableVisitor extends GJDepthFirst<Void, Map<String, String>> {
        private final Set<String> lambdaParams;
        private final MethodInfo surroundMethod;
        FreeVariableVisitor(Set<String> lp, MethodInfo sm) { this.lambdaParams = lp; this.surroundMethod = sm; }
        @Override
        public Void visit(Identifier n, Map<String, String> freeVars) {
            String id = n.f0.tokenImage;
            if (!lambdaParams.contains(id)) {
                if (surroundMethod.locals.containsKey(id)) freeVars.put(id, surroundMethod.locals.get(id));
                else if (surroundMethod.params.containsKey(id)) freeVars.put(id, surroundMethod.params.get(id));
            }
            return null;
        }
    }

    @Override
    public String visit(Goal n, Void argu) {
        n.accept(new LambdaTypeVisitor(), new HashSet<>());
        n.f0.accept(this, argu);
        n.f1.accept(this, argu);
        if (n.f2.present()) n.f2.accept(this, argu);
        return null;
    }

    @Override
    public String visit(AssignmentStatement n, Void argu) {
        String varName = n.f0.f0.tokenImage;
        String varType = null;
        if (currentMethod != null && currentMethod.locals.containsKey(varName)) varType = currentMethod.locals.get(varName);
        else if (currentClass != null && currentClass.fields.containsKey(varName)) varType = currentClass.fields.get(varName);

        Expression expressionNode = n.f2;
        if (varType != null && varType.startsWith("Func_") && expressionNode.f0.choice instanceof LambdaExpression) {
            LambdaExpression lambdaExprNode = (LambdaExpression) expressionNode.f0.choice;
            String lambdaClassName = "Lambda$" + lambdaCounter++;
            st.addClass(lambdaClassName, varType);
            ClassInfo lambdaClass = st.getClass(lambdaClassName);
            Identifier paramIdentifier = (Identifier) lambdaExprNode.f1;
            String paramName = paramIdentifier.f0.tokenImage;
            ClassInfo baseFuncClass = st.getClass(varType);
            MethodInfo baseApplyMethod = baseFuncClass.methods.get("apply");
            String paramType = baseApplyMethod.params.values().iterator().next();
            Set<String> lambdaParamNames = new HashSet<>();
            lambdaParamNames.add(paramName);
            FreeVariableVisitor fvVisitor = new FreeVariableVisitor(lambdaParamNames, currentMethod);
            Map<String, String> freeVars = new LinkedHashMap<>();
            freeVars.put("this_", currentClass.name);
            lambdaExprNode.f4.accept(fvVisitor, freeVars);
            lambdaClass.fields.putAll(freeVars);
            String returnType = baseApplyMethod.returnType;
            MethodInfo applyMethod = new MethodInfo();
            applyMethod.name = "apply";
            applyMethod.returnType = returnType;
            applyMethod.params.put(paramName, paramType);
            applyMethod.body = lambdaExprNode.f4;
            lambdaClass.methods.put("apply", applyMethod);
            lambdaClassMap.put(lambdaExprNode, lambdaClass);
            return null;
        }
        n.f0.accept(this, argu);
        n.f2.accept(this, argu);
        return null;
    }
    
    @Override
    public String visit(VarDeclaration n, Void argu) {
        String typeName = n.f0.accept(this, argu);
        String varName = n.f1.f0.tokenImage;
        if (currentMethod == null) currentClass.fields.put(varName, typeName);
        else currentMethod.locals.put(varName, typeName);
        return null;
    }

    @Override 
    public String visit(MainClass n, Void argu) {
        String className = n.f1.f0.tokenImage;
        st.addClass(className, null);
        currentClass = st.getClass(className);
        MethodInfo mainMethod = new MethodInfo();
        mainMethod.name = "main";
        mainMethod.returnType = "void";
        mainMethod.params.put(n.f11.f0.tokenImage, "String[]");
        currentClass.methods.put("main", mainMethod);
        currentMethod = mainMethod;
        n.f14.accept(this, argu);
        currentMethod = null;
        currentClass = null;
        return null;
    }

    @Override 
    public String visit(TypeDeclaration n, Void argu) { 
        return n.f0.accept(this, argu); 
    }

    @Override 
    public String visit(ClassDeclaration n, Void argu) {
        String className = n.f1.f0.tokenImage;
        st.addClass(className, null);
        currentClass = st.getClass(className);
        n.f3.accept(this, argu);
        n.f4.accept(this, argu);
        currentClass = null;
        return null;
    }

    @Override 
    public String visit(ClassExtendsDeclaration n, Void argu) {
        String className = n.f1.f0.tokenImage;
        String parentName = n.f3.f0.tokenImage;
        st.addClass(className, parentName);
        currentClass = st.getClass(className);
        n.f5.accept(this, argu);
        n.f6.accept(this, argu);
        currentClass = null;
        return null;
    }
    
    @Override 
    public String visit(MethodDeclaration n, Void argu) {
        String returnType = n.f1.accept(this, argu);
        String methodName = n.f2.f0.tokenImage;
        currentMethod = new MethodInfo();
        currentMethod.name = methodName;
        currentMethod.returnType = returnType;
        currentMethod.body = n.f10;
        if (n.f4.present()) n.f4.node.accept(this, argu);
        currentClass.methods.put(methodName, currentMethod);
        if (n.f7.present()) for (Node node : n.f7.nodes) node.accept(this, argu);
        if (n.f8.present()) for (Node node : n.f8.nodes) node.accept(this, argu);
        n.f10.accept(this, argu);
        currentMethod = null;
        return null;
    }
    
    @Override 
    public String visit(FormalParameterList n, Void argu) {
        n.f0.accept(this, argu);
        for (Node node : n.f1.nodes) node.accept(this, argu);
        return null;
    }
    
    @Override 
    public String visit(FormalParameter n, Void argu) {
        String type = n.f0.accept(this, argu);
        String name = n.f1.f0.tokenImage;
        currentMethod.params.put(name, type);
        return null;
    }
    
    @Override 
    public String visit(FormalParameterRest n, Void argu) { 
        return n.f1.accept(this, argu); 
    }
    
    @Override 
    public String visit(Type n, Void argu) { 
        return n.f0.accept(this, argu); 
    }
    
    @Override 
    public String visit(ArrayType n, Void argu) { 
        return "int[]"; 
    }
    
    @Override 
    public String visit(BooleanType n, Void argu) { 
        return "boolean"; 
    }
    
    @Override 
    public String visit(IntegerType n, Void argu) { 
        return "int"; 
    }
    
    @Override 
    public String visit(Identifier n, Void argu) { 
        return n.f0.tokenImage; 
    }
    
    @Override 
    public String visit(ImportFunction n, Void argu) { 
        return null; 
    }
    
    @Override 
    public String visit(LambdaType n, Void argu) {
        String t1 = n.f2.accept(new TypeVisitor(), null);
        String t2 = n.f4.accept(new TypeVisitor(), null);
        return "Func_" + t1 + "_" + t2;
    }

    @Override 
    public String visit(LambdaExpression n, Void argu) {
        if (lambdaClassMap.containsKey(n)) return lambdaClassMap.get(n).name;
        return "lambda";
    }
}