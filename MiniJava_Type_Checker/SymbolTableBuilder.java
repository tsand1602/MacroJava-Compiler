import syntaxtree.*;
import visitor.GJDepthFirst;
import java.util.*;

public class SymbolTableBuilder extends GJDepthFirst<SymbolTableBuilder.MyType, SymbolTableBuilder.Context> {
    public static boolean hasError = false;
    public static String errorMessage = null;

    public boolean hadError() { return hasError; }
    public String getErrorType() { return errorMessage; }
    public static void error(String msg) {
        errorMessage = msg;
        hasError = true;
    }

    private SymbolTable symtab = new SymbolTable();
    public SymbolTable getSymbolTable() { return symtab; }

    public static class Context {
        public final SymbolTable table;
        public final String currentClass;
        public final String currentMethod;
        public final MyType expectedType;
        public boolean inType = false;

        public Deque<MethodInfo> lambdaScopes = new ArrayDeque<>();
        public void pushLambda(MethodInfo mi) { lambdaScopes.push(mi); }
        public void popLambda() { lambdaScopes.pop(); }
        public MethodInfo currentScope(SymbolTable symtab) {
            if (!lambdaScopes.isEmpty()) return lambdaScopes.peek();
            if (currentClass == null || currentMethod == null) return null;
            ClassInfo ci = symtab.getClass(currentClass);
            return ci != null ? ci.methods.get(currentMethod) : null;
        }

        public Context(SymbolTable table, String currentClass, String currentMethod) {
            this.table = table;
            this.currentClass = currentClass;
            this.currentMethod = currentMethod;
            this.expectedType = null;
        }
        public Context(SymbolTable table, String cls, String method, MyType expectedType) {
            this.table = table;
            this.currentClass = cls;
            this.currentMethod = method;
            this.expectedType = expectedType;
        }
        public SymbolTable table() { return table; }
        public String currentClass() { return currentClass; }
        public String currentMethod() { return currentMethod; }
        public Context withInType(boolean flag) {
            Context ctx = new Context(table, currentClass, currentMethod);
            ctx.inType = flag;
            return ctx;
        }
    }

    public static abstract class MyType {
        @Override public abstract boolean equals(Object o);
        @Override public abstract String toString();
    }

    public static class MyErrorType extends MyType {
        @Override public boolean equals(Object o) { return o instanceof MyErrorType; }
        @Override public String toString() { return "error"; }
    }

    public static class MySymbolNotFoundType extends MyType {
        @Override public boolean equals(Object o) { return o instanceof MySymbolNotFoundType; }
        @Override public String toString() { return "symbol not found"; }
    }

    public static class MyLambdaType extends MyType {
        public final MyType argType;
        public final MyType retType;

        public MyLambdaType(MyType argType, MyType retType) {
            this.argType = argType;
            this.retType = retType;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MyLambdaType)) return false;
            MyLambdaType other = (MyLambdaType) o;
            return this.argType.equals(other.argType) &&
                   this.retType.equals(other.retType);
        }

        @Override
        public String toString() {
            return "Function<" + argType.toString() + "," + retType.toString() + ">";
        }
    }

    public static class MyIntType extends MyType {
        @Override public boolean equals(Object o) { return o instanceof MyIntType; }
        @Override public String toString() { return "int"; }
    }

    public static class MyBooleanType extends MyType {
        @Override public boolean equals(Object o) { return o instanceof MyBooleanType; }
        @Override public String toString() { return "boolean"; }
    }

    public static class MyIntArrayType extends MyType {
        @Override public boolean equals(Object o) { return o instanceof MyIntArrayType; }
        @Override public String toString() { return "int[]"; }
    }

    public static class MyVoidType extends MyType {
        @Override public boolean equals(Object o) { return o instanceof MyVoidType; }
        @Override public String toString() { return "void"; }
    }

    public static class MyClassType extends MyType {
        public final String className;

        public MyClassType(String className) {
            this.className = className;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MyClassType)) return false;
            MyClassType other = (MyClassType) o;
            return this.className.equals(other.className);
        }

        @Override
        public String toString() {
            return className;
        }
    }

    public static class MyUnknownType extends MyType {
        public final String name;
        public MyUnknownType(String n) { this.name = n; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof MyUnknownType)) return false;
            MyUnknownType other = (MyUnknownType)o;
            return this.name != null && this.name.equals(other.name);
        }
        @Override public String toString() { return name; }
    }

    public static class ClassInfo {
        public String name;
        public String parent;
        public Map<String, MyType> fields = new LinkedHashMap<>();
        public Map<String, MethodInfo> methods = new LinkedHashMap<>();
    }

    public static class MethodInfo {
        public String name;
        public MyType returnType;
        public LinkedHashMap<String, MyType> parameters = new LinkedHashMap<>();
        public List<MyType> paramList = new ArrayList<>();
        public Map<String, MyType> locals = new LinkedHashMap<>();
    }

    public static class SymbolTable {
        public Map<String, ClassInfo> classes = new LinkedHashMap<>();
        public boolean hasImport = false;
        public void addClass(String name, String parent) {
            if (classes.containsKey(name)) {
                SymbolTableBuilder.error("Symbol not found");
                return;
            }
            ClassInfo ci = new ClassInfo();
            ci.name = name;
            ci.parent = parent;
            classes.put(name, ci);
        }

        public ClassInfo getClass(String name) { return classes.get(name); }
        public boolean hasClass(String name) { return classes.containsKey(name); }
    }

    public static boolean isErrorType(MyType t) {
        return t instanceof MyErrorType;
    }
    public static boolean isSymbolNotFoundType(MyType t) {
        return t instanceof MySymbolNotFoundType;
    }

    @Override
    public MyType visit(Goal n, Context ctx) {
        Context top = new Context(symtab, null, null);

        n.f0.accept(this, top);
        n.f1.accept(this, top);
        n.f2.accept(this, top);
        n.f3.accept(this, top);
        if (!hasError) validateParentsAndDetectCycles();

        if (!hasError) {
            n.f0.accept(new Collector(), top);
            n.f1.accept(new Collector(), top);
            n.f2.accept(new Collector(), top);
        }

        return null;
    }

    @Override
    public MyType visit(MainClass n, Context ctx) {
        String cname = n.f1.f0.toString();
        ctx.table().addClass(cname, null);
        return null;
    }

    @Override
    public MyType visit(LambdaType n, Context ctx) {
        if (!ctx.table.hasImport) {
            error("Symbol not found");
            return new MySymbolNotFoundType();
        }
        MyType arg = n.f2.accept(this, ctx.withInType(true));
        MyType ret = n.f4.accept(this, ctx.withInType(true));
        if (isSymbolNotFoundType(arg) || isSymbolNotFoundType(ret) || isErrorType(arg) || isErrorType(ret)) {
            error("Symbol not found");
            return new MySymbolNotFoundType();
        }
        return new MyLambdaType(arg, ret);
    }

    @Override
    public MyType visit(ImportFunction n, Context ctx) {
        ctx.table.hasImport = true;
        return null;
    }

    @Override
    public MyType visit(ClassDeclaration n, Context ctx) {
        String cname = n.f1.f0.toString();
        ctx.table().addClass(cname, null);
        return null;
    }

    @Override
    public MyType visit(ClassExtendsDeclaration n, Context ctx) {
        String cname = n.f1.f0.toString();
        String pname = n.f3.f0.toString();
        ctx.table().addClass(cname, pname);
        return null;
    }

    @Override
    public MyType visit(VarDeclaration n, Context ctx) {
        return null;
    }

    @Override public MyType visit(TypeDeclaration n, Context ctx) {
        return n.f0.accept(this, ctx);
    }

    @Override public MyType visit(IntegerType n, Context ctx) { return new MyIntType(); }

    @Override public MyType visit(BooleanType n, Context ctx) { return new MyBooleanType(); }

    @Override public MyType visit(ArrayType n, Context ctx) { return new MyIntArrayType(); }

    @Override
    public MyType visit(Identifier n, Context ctx) {
        String name = n.f0.toString();

        if (ctx.inType) {
            switch (name) {
                case "Integer": return new MyIntType();
                case "Boolean": return new MyBooleanType();
            }
            if (ctx.table.hasClass(name)) {
                return new MyClassType(name);
            }
            error("Symbol not found");
            return new MySymbolNotFoundType();
        } else {
            MethodInfo scope = ctx.currentScope(symtab);
            if (scope != null && scope.locals.containsKey(name)) {
                return scope.locals.get(name);
            }
            if (scope != null && scope.parameters.containsKey(name)) {
                return scope.parameters.get(name);
            }
            ClassInfo ci = ctx.currentClass != null ? symtab.getClass(ctx.currentClass) : null;
            while (ci != null) {
                if (ci.fields.containsKey(name)) {
                    return ci.fields.get(name);
                }
                ci = (ci.parent != null) ? symtab.getClass(ci.parent) : null;
            }
            if (symtab.hasClass(name)) {
                return new MyClassType(name);
            }
            error("Symbol not found");
            return new MySymbolNotFoundType();
        }
    }

    private class Collector extends GJDepthFirst<MyType, Context> {
        @Override
        public MyType visit(MainClass n, Context ctx) {
            String cname = n.f1.f0.toString();
            ClassInfo ci = symtab.getClass(cname);
            MethodInfo mi = new MethodInfo();
            mi.name = "main";
            mi.returnType = new MyVoidType();
            try {
                String argname = n.f11.f0.toString();
                mi.parameters.put(argname, new MyUnknownType("String[]"));
                mi.paramList.add(new MyUnknownType("String[]"));
            } catch (Exception e) {
            }
            ci.methods.put("main", mi);
            return null;
        }

        @Override
        public MyType visit(ClassDeclaration n, Context ctx) {
            String cname = n.f1.f0.toString();
            Context newCtx = new Context(symtab, cname, null);
            n.f3.accept(this, newCtx);
            n.f4.accept(this, newCtx);
            return null;
        }

        @Override
        public MyType visit(Type n, Context ctx) {
            return n.f0.accept(this, ctx.withInType(true));
        }

        @Override
        public MyType visit(LambdaType n, Context ctx) {
            if (!ctx.table.hasImport) {
                SymbolTableBuilder.error("Symbol not found");
                return new MySymbolNotFoundType();
            }
            MyType arg = n.f2.accept(this, ctx.withInType(true));
            MyType ret = n.f4.accept(this, ctx.withInType(true));
            if (isSymbolNotFoundType(arg) || isSymbolNotFoundType(ret) || isErrorType(arg) || isErrorType(ret)) {
                SymbolTableBuilder.error("Symbol not found");
                return new MySymbolNotFoundType();
            }
            return new MyLambdaType(arg, ret);
        }

        @Override
        public MyType visit(ClassExtendsDeclaration n, Context ctx) {
            String cname = n.f1.f0.toString();
            Context newCtx = new Context(symtab, cname, null);
            n.f5.accept(this, newCtx);
            n.f6.accept(this, newCtx);
            return null;
        }

        @Override
        public MyType visit(VarDeclaration n, Context ctx) {
            MyType t = n.f0.accept(this, ctx);
            String vname = n.f1.f0.toString();
            if (ctx.currentMethod() != null) {
                ClassInfo ci = ctx.table().getClass(ctx.currentClass());
                if (ci == null) { SymbolTableBuilder.error("Symbol not found"); return null; }
                MethodInfo mi = ci.methods.get(ctx.currentMethod());
                if (mi == null) { SymbolTableBuilder.error("Symbol not found"); return null; }
                if (mi.locals.containsKey(vname) || mi.parameters.containsKey(vname)) {
                    SymbolTableBuilder.error("Symbol not found");
                } else {
                    mi.locals.put(vname, t);
                }
            } else {
                ClassInfo ci = ctx.table().getClass(ctx.currentClass());
                if (ci == null) { SymbolTableBuilder.error("Symbol not found"); return null; }
                if (ci.fields.containsKey(vname)) {
                    SymbolTableBuilder.error("Symbol not found");
                } else {
                    ci.fields.put(vname, t);
                }
            }
            return null;
        }

        @Override
        public MyType visit(Block n, Context ctx) {
            for (Node stmt : n.f1.nodes) {
                stmt.accept(this, ctx);
            }
            return null;
        }

        @Override
        public MyType visit(MethodDeclaration n, Context ctx) {
            MyType rtype = n.f1.accept(this, ctx);
            if (rtype == null || isSymbolNotFoundType(rtype) || isErrorType(rtype)) {
                SymbolTableBuilder.error("Symbol not found");
                return null;
            }

            String mname = n.f2.f0.toString();
            ClassInfo ci = ctx.table().getClass(ctx.currentClass());
            if (ci == null) {
                SymbolTableBuilder.error("Symbol not found");
                return null;
            }

            if (ci.methods.containsKey(mname)) {
                SymbolTableBuilder.error("Type error");
                return null;
            }

            MethodInfo mi = new MethodInfo();
            mi.name = mname;
            mi.returnType = rtype;

            ci.methods.put(mname, mi);

            Context newCtx = new Context(ctx.table, ctx.currentClass, mname);

            if (n.f4.present()) {
                n.f4.node.accept(this, newCtx);
            }

            n.f7.accept(this, newCtx);
            if (ci.parent != null) {
                ClassInfo parent = ctx.table().getClass(ci.parent);
                while (parent != null) {
                    MethodInfo pm = parent.methods.get(mname);
                    if (pm != null) {
                        if (!pm.returnType.equals(rtype) ||
                            pm.parameters.size() != mi.parameters.size() ||
                            !new ArrayList<>(pm.parameters.values())
                                .equals(new ArrayList<>(mi.parameters.values()))) {
                            SymbolTableBuilder.error("Type error");
                            return null;
                        }
                        break;
                    }
                    if (parent.parent == null) break;
                    parent = ctx.table().getClass(parent.parent);
                }
            }

            return null;
        }

        @Override
        public MyType visit(FormalParameterList n, Context ctx) {
            n.f0.accept(this, ctx);
            n.f1.accept(this, ctx);
            return null;
        }

        @Override
        public MyType visit(FormalParameterRest n, Context ctx) {
            n.f1.accept(this, ctx);
            return null;
        }

        @Override
        public MyType visit(FormalParameter n, Context ctx) {
            MyType t = n.f0.accept(this, ctx);
            String pname = n.f1.f0.toString();
            ClassInfo ci = ctx.table().getClass(ctx.currentClass());
            if (ci == null) { SymbolTableBuilder.error("Symbol not found"); return null; }
            MethodInfo mi = ci.methods.get(ctx.currentMethod());
            if (mi == null) { SymbolTableBuilder.error("Symbol not found"); return null; }
            if (mi.parameters.containsKey(pname)) {
                SymbolTableBuilder.error("Type error");
            } else {
                mi.parameters.put(pname, t);
                mi.paramList.add(t);
            }
            return null;
        }

        @Override public MyType visit(IntegerType n, Context ctx) { return new MyIntType(); }

        @Override public MyType visit(BooleanType n, Context ctx) { return new MyBooleanType(); }

        @Override public MyType visit(ArrayType n, Context ctx) { return new MyIntArrayType(); }

        @Override
        public MyType visit(Identifier n, Context ctx) {
            String name = n.f0.toString();

            if (ctx.inType) {
                switch (name) {
                    case "Integer": return new MyIntType();
                    case "Boolean": return new MyBooleanType();
                }
                if (ctx.table.hasClass(name)) {
                    return new MyClassType(name);
                }
                error("Symbol not found");
                return new MySymbolNotFoundType();
            } else {
                MethodInfo scope = ctx.currentScope(symtab);
                if (scope != null && scope.locals.containsKey(name)) {
                    return scope.locals.get(name);
                }
                if (scope != null && scope.parameters.containsKey(name)) {
                    return scope.parameters.get(name);
                }
                ClassInfo ci = ctx.currentClass != null ? symtab.getClass(ctx.currentClass) : null;
                while (ci != null) {
                    if (ci.fields.containsKey(name)) {
                        return ci.fields.get(name);
                    }
                    ci = (ci.parent != null) ? symtab.getClass(ci.parent) : null;
                }
                if (symtab.hasClass(name)) {
                    return new MyClassType(name);
                }
                error("Symbol not found");
                return new MySymbolNotFoundType();
            }
        }
    }

    private void validateParentsAndDetectCycles() {
        for (Map.Entry<String, ClassInfo> e : symtab.classes.entrySet()) {
            String parent = e.getValue().parent;
            if (parent != null && !symtab.classes.containsKey(parent)) {
                error("Symbol not found");
                return;
            }
        }

        Map<String, Integer> state = new HashMap<>();
        for (String cls : symtab.classes.keySet()) {
            if (state.getOrDefault(cls,0) == 0) {
                if (dfsCycle(cls, state)) { 
                    error("Type error"); 
                    return; 
                }
            }
        }
    }
    
    private boolean dfsCycle(String cls, Map<String,Integer> state) {
        state.put(cls, 1);
        ClassInfo ci = symtab.classes.get(cls);
        if (ci != null && ci.parent != null) {
            String p = ci.parent;
            int st = state.getOrDefault(p, 0);
            if (st == 1) return true;
            if (st == 0) {
                if (dfsCycle(p, state)) return true;
            }
        }
        state.put(cls, 2);
        return false;
    }
}
