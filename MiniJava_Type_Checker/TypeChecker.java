import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import syntaxtree.*;
import visitor.GJDepthFirst;

class TypeChecker extends GJDepthFirst<SymbolTableBuilder.MyType, SymbolTableBuilder.Context> {
    private final SymbolTableBuilder.SymbolTable symtab;

    public TypeChecker(SymbolTableBuilder.SymbolTable st) { this.symtab = st; }

    private void error(String msg) { SymbolTableBuilder.error(msg); }

    private SymbolTableBuilder.MyType errorType() {
        return new SymbolTableBuilder.MyErrorType();
    }
    private SymbolTableBuilder.MyType symbolNotFoundType() {
        return new SymbolTableBuilder.MySymbolNotFoundType();
    }

    private boolean isErrorType(SymbolTableBuilder.MyType t) {
        return (t instanceof SymbolTableBuilder.MyErrorType);
    }
    private boolean isSymbolNotFoundType(SymbolTableBuilder.MyType t) {
        return (t instanceof SymbolTableBuilder.MySymbolNotFoundType);
    }

    private String typeName(SymbolTableBuilder.MyType t) {
        if (t == null) return null;
        if (t instanceof SymbolTableBuilder.MyClassType) return ((SymbolTableBuilder.MyClassType)t).className;
        if (t instanceof SymbolTableBuilder.MyUnknownType) return ((SymbolTableBuilder.MyUnknownType)t).name;
        return null;
    }

    private SymbolTableBuilder.MyType lookupVar(SymbolTableBuilder.Context ctx, String vname) {
        for (Iterator<SymbolTableBuilder.MethodInfo> it = ctx.lambdaScopes.iterator(); it.hasNext(); ) {
            SymbolTableBuilder.MethodInfo lam = it.next();
            if (lam.parameters.containsKey(vname)) return lam.parameters.get(vname);
            if (lam.locals.containsKey(vname)) return lam.locals.get(vname);
        }

        if (ctx.currentClass() != null && ctx.currentMethod() != null) {
            SymbolTableBuilder.ClassInfo ci = ctx.table().getClass(ctx.currentClass());
            if (ci != null) {
                 SymbolTableBuilder.MethodInfo mi = ci.methods.get(ctx.currentMethod());
                if (mi != null) {
                    if (mi.locals.containsKey(vname)) return mi.locals.get(vname);
                    if (mi.parameters.containsKey(vname)) return mi.parameters.get(vname);
                }
            }
        }

        if (ctx.currentClass() != null) {
            SymbolTableBuilder.ClassInfo cur = ctx.table().getClass(ctx.currentClass());
            while (cur != null) {
                if (cur.fields.containsKey(vname)) return cur.fields.get(vname);
                cur = (cur.parent == null ? null : ctx.table().getClass(cur.parent));
            }
        }

        if (ctx.table().hasClass(vname)) return new SymbolTableBuilder.MyClassType(vname);

        SymbolTableBuilder.error("Symbol not found");
        return symbolNotFoundType();
    }

    private boolean isAssignable(SymbolTableBuilder.MyType to, SymbolTableBuilder.MyType from) {
        if (to == null || from == null) return false;
        if (isErrorType(to) || isErrorType(from)) return true;
        if (isSymbolNotFoundType(to) || isSymbolNotFoundType(from)) return true;
        if (to.equals(from)) return true;

        if (to instanceof SymbolTableBuilder.MyClassType && from instanceof SymbolTableBuilder.MyClassType) {
            String toName = ((SymbolTableBuilder.MyClassType)to).className;
            String fromName = ((SymbolTableBuilder.MyClassType)from).className;
            return isSubclass(fromName, toName);
        }

        return false;
    }

    private boolean isSubclass(String child, String parent) {
        if (child.equals(parent)) return true;
        SymbolTableBuilder.ClassInfo ci = symtab.getClass(child);
        while (ci != null && ci.parent != null) {
            if (ci.parent.equals(parent)) return true;
            ci = symtab.getClass(ci.parent);
        }
        return false;
    }

    @Override
    public SymbolTableBuilder.MyType visit(MessageSend n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType recvType = n.f0.accept(this, ctx);
        if (recvType == null || isErrorType(recvType) || isSymbolNotFoundType(recvType)) {
            if (!SymbolTableBuilder.hasError) error("Symbol not found");
            return symbolNotFoundType();
        }

        String methodName = n.f2.f0.toString();

        if (recvType instanceof SymbolTableBuilder.MyLambdaType lamType) {
            if (!methodName.equals("apply")) {
                error("Type error");
                return errorType();
            }

            List<SymbolTableBuilder.MyType> argTypes = new ArrayList<>();
            if (n.f4.present()) {
                ExpressionList el = (ExpressionList) n.f4.node;
                SymbolTableBuilder.Context argCtx = new SymbolTableBuilder.Context(ctx.table(), ctx.currentClass(), ctx.currentMethod(), lamType.argType);
                argCtx.lambdaScopes = ctx.lambdaScopes;
                argTypes.add(el.f0.accept(this, argCtx));

                for (Node restNode : el.f1.nodes) {
                    ExpressionRest er = (ExpressionRest) restNode;
                    argTypes.add(er.f1.accept(this, argCtx));
                }
            }

            if (argTypes.size() != 1) {
                error("Type error");
                return errorType();
            }

            SymbolTableBuilder.MyType actual = argTypes.get(0);
            if (!isAssignable(lamType.argType, actual)) {
                error("Type error");
                return errorType();
            }

            return lamType.retType;
        }

        if (recvType instanceof SymbolTableBuilder.MyClassType ||
            recvType instanceof SymbolTableBuilder.MyUnknownType) {

            String recvClassName = typeName(recvType);
            if (recvClassName == null || !symtab.hasClass(recvClassName)) {
                error("Symbol not found");
                return symbolNotFoundType();
            }

            SymbolTableBuilder.ClassInfo ci = symtab.getClass(recvClassName);
            SymbolTableBuilder.MethodInfo mi = null;
            while (ci != null) {
                mi = ci.methods.get(methodName);
                if (mi != null) break;
                ci = (ci.parent == null ? null : symtab.getClass(ci.parent));
            }
            if (mi == null) {
                error("Symbol not found");
                return symbolNotFoundType();
            }

            List<SymbolTableBuilder.MyType> argTypes = new ArrayList<>();
            if (n.f4.present()) {
                ExpressionList el = (ExpressionList) n.f4.node;
                if (mi.paramList.size() >= 1) {
                    SymbolTableBuilder.MyType formal = mi.paramList.get(0);
                    SymbolTableBuilder.Context argCtx = new SymbolTableBuilder.Context(ctx.table(), ctx.currentClass(), ctx.currentMethod(), formal);
                    argCtx.lambdaScopes = ctx.lambdaScopes;
                    argTypes.add(el.f0.accept(this, argCtx));
                } else {
                    argTypes.add(el.f0.accept(this, ctx));
                }
                for (int idx = 0; idx < el.f1.nodes.size(); ++idx) {
                    ExpressionRest er = (ExpressionRest) el.f1.nodes.get(idx);
                    int formalIdx = idx + 1;
                    if (formalIdx < mi.paramList.size()) {
                        SymbolTableBuilder.MyType formal = mi.paramList.get(formalIdx);
                        SymbolTableBuilder.Context argCtx = new SymbolTableBuilder.Context(ctx.table(), ctx.currentClass(), ctx.currentMethod(), formal);
                        argCtx.lambdaScopes = ctx.lambdaScopes;
                        argTypes.add(er.f1.accept(this, argCtx));
                    } else {
                        argTypes.add(er.f1.accept(this, ctx));
                    }
                }
            }

            if (argTypes.size() != mi.paramList.size()) {
                error("Type error");
                return errorType();
            }

            for (int i = 0; i < argTypes.size(); ++i) {
                SymbolTableBuilder.MyType actual = argTypes.get(i);
                if (isSymbolNotFoundType(actual) || isErrorType(actual)) return actual;
                if (!isAssignable(mi.paramList.get(i), actual)) {
                    error("Type error");
                    return errorType();
                }
            }
            return mi.returnType;
        }
        if (recvType instanceof SymbolTableBuilder.MyIntType ||
            recvType instanceof SymbolTableBuilder.MyBooleanType) {
            error("Type error");
            return errorType();
        } else {
            error("Symbol not found");
            return symbolNotFoundType();
        }
    }

    @Override
    public SymbolTableBuilder.MyType visit(MethodDeclaration n, SymbolTableBuilder.Context ctx) {
        String cname = ctx.currentClass();
        String mname = n.f2.f0.toString();
        SymbolTableBuilder.Context methodCtx = new SymbolTableBuilder.Context(symtab, cname, mname);

        n.f7.accept(this, methodCtx);
        n.f8.accept(this, methodCtx);

        SymbolTableBuilder.ClassInfo ci = symtab.getClass(cname);
        if (ci == null) { error("Symbol not found"); return null; }
        SymbolTableBuilder.MethodInfo mi = ci.methods.get(mname);
        if (mi == null) { error("Symbol not found"); return null; }

        SymbolTableBuilder.MyType declaredReturn = mi.returnType;

        SymbolTableBuilder.Context returnCtx = new SymbolTableBuilder.Context(symtab, cname, mname, declaredReturn);
        returnCtx.lambdaScopes = methodCtx.lambdaScopes;
        SymbolTableBuilder.MyType actualReturn = n.f10.accept(this, returnCtx);

        if (actualReturn == null || isErrorType(actualReturn) || isSymbolNotFoundType(actualReturn)) {
            return null;
        }

        if (!isAssignable(declaredReturn, actualReturn)) {
            error("Type error");
        }
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(LambdaExpression n, SymbolTableBuilder.Context ctx) {
        if (!(ctx.expectedType instanceof SymbolTableBuilder.MyLambdaType lamType)) {
            error("Type error");
            return errorType();
        }

        String paramName = n.f1.f0.toString();
        SymbolTableBuilder.MyType paramType = lamType.argType;
        SymbolTableBuilder.MyType returnType = lamType.retType;

        if (ctx.currentMethod() != null) {
            SymbolTableBuilder.ClassInfo ci = ctx.table().getClass(ctx.currentClass());
            SymbolTableBuilder.MethodInfo mi = ci.methods.get(ctx.currentMethod());
            if (mi.parameters.containsKey(paramName) || mi.locals.containsKey(paramName)) {
                error("Type error");
                return errorType();
            }
        }

        SymbolTableBuilder.MethodInfo lam = new SymbolTableBuilder.MethodInfo();
        lam.name = "<lambda>";
        lam.returnType = returnType;
        lam.parameters.put(paramName, paramType);

        ctx.lambdaScopes.push(lam);

        SymbolTableBuilder.Context bodyCtx = new SymbolTableBuilder.Context(ctx.table(), ctx.currentClass(), ctx.currentMethod(), returnType);
        bodyCtx.lambdaScopes = ctx.lambdaScopes;
        SymbolTableBuilder.MyType bodyType = n.f4.accept(this, bodyCtx);

        ctx.lambdaScopes.pop();

        if (isSymbolNotFoundType(bodyType) || isErrorType(bodyType)) return bodyType;

        if (!isAssignable(returnType, bodyType)) {
            error("Type error");
            return errorType();
        }

        return lamType;
    }
    
    @Override
    public SymbolTableBuilder.MyType visit(AssignmentStatement n, SymbolTableBuilder.Context ctx) {
        String varName = n.f0.f0.toString();
        SymbolTableBuilder.MyType lhsType = lookupVar(ctx, varName);
        if (isSymbolNotFoundType(lhsType) || isErrorType(lhsType)) return lhsType;

        SymbolTableBuilder.Context rhsCtx = new SymbolTableBuilder.Context(ctx.table(), ctx.currentClass(), ctx.currentMethod(), lhsType);
        rhsCtx.lambdaScopes = ctx.lambdaScopes;
        SymbolTableBuilder.MyType rhsType = n.f2.accept(this, rhsCtx);

        if (isSymbolNotFoundType(rhsType) || isErrorType(rhsType)) return rhsType;

        if (!isAssignable(lhsType, rhsType)) {
            error("Type error");
            return errorType();
        }

        return null;
    }
    
    @Override
    public SymbolTableBuilder.MyType visit(ClassDeclaration n, SymbolTableBuilder.Context ctx) {
        String cname = n.f1.f0.toString();
        SymbolTableBuilder.Context newCtx = new SymbolTableBuilder.Context(symtab, cname, null);
        n.f3.accept(this, newCtx);
        n.f4.accept(this, newCtx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(ClassExtendsDeclaration n, SymbolTableBuilder.Context ctx) {
        String cname = n.f1.f0.toString();
        SymbolTableBuilder.Context newCtx = new SymbolTableBuilder.Context(symtab, cname, null);
        n.f5.accept(this, newCtx);
        n.f6.accept(this, newCtx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(VarDeclaration n, SymbolTableBuilder.Context ctx) {
        n.f0.accept(this, ctx);
        n.f1.accept(this, ctx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(Statement n, SymbolTableBuilder.Context ctx) {
        return n.f0.accept(this, ctx);
    }

    @Override
    public SymbolTableBuilder.MyType visit(Block n, SymbolTableBuilder.Context ctx) {
        for (Node stmt : n.f1.nodes) {
            stmt.accept(this, ctx);
        }
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(Expression n, SymbolTableBuilder.Context ctx) {
        return n.f0.accept(this, ctx);
    }

    private SymbolTableBuilder.MyType resolveTypeName(String name) {
        if ("Integer".equals(name)) return new SymbolTableBuilder.MyIntType();
        if ("Boolean".equals(name)) return new SymbolTableBuilder.MyBooleanType();
        if (symtab.hasClass(name)) return new SymbolTableBuilder.MyClassType(name);
        error("Symbol not found");
        return symbolNotFoundType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(PrimaryExpression n, SymbolTableBuilder.Context ctx) {
        return n.f0.accept(this, ctx);
    }

    @Override
    public SymbolTableBuilder.MyType visit(LambdaType n, SymbolTableBuilder.Context ctx) {
        if (!symtab.hasImport) {
            error("Symbol not found");
            return symbolNotFoundType();
        }
        SymbolTableBuilder.MyType arg = n.f2.accept(this, ctx.withInType(true));
        SymbolTableBuilder.MyType ret = n.f4.accept(this, ctx.withInType(true));
        if (isErrorType(arg) || isErrorType(ret) || isSymbolNotFoundType(arg) || isSymbolNotFoundType(ret)) {
            if (!SymbolTableBuilder.hasError) {
                error("Symbol not found");
            }
            return symbolNotFoundType();
        }
        return new SymbolTableBuilder.MyLambdaType(arg, ret);
    }

    @Override
    public SymbolTableBuilder.MyType visit(Type n, SymbolTableBuilder.Context ctx) {
        ctx.inType = true;
        SymbolTableBuilder.MyType t = n.f0.accept(this, ctx);
        ctx.inType = false;
        return t;
    }

    @Override
    public SymbolTableBuilder.MyType visit(TypeDeclaration n, SymbolTableBuilder.Context ctx) {
        return n.f0.accept(this, ctx);
    }

    @Override
    public SymbolTableBuilder.MyType visit(Goal n, SymbolTableBuilder.Context ctx) {
        n.f0.accept(this, ctx);
        n.f1.accept(this, ctx);
        n.f2.accept(this, ctx);
        n.f3.accept(this, ctx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(MainClass n, SymbolTableBuilder.Context ctx) {
        String className = n.f1.f0.toString();
        SymbolTableBuilder.ClassInfo ci = symtab.classes.get(className);
        if (ci == null) {
            error("Symbol not found");
            return null;
        }
        SymbolTableBuilder.Context newCtx = new SymbolTableBuilder.Context(symtab, ci.name, "main");
        n.f14.accept(this, newCtx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(PrintStatement n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType t = n.f2.accept(this, ctx);
        if (isErrorType(t) || isSymbolNotFoundType(t)) return t;
        if (!(t instanceof SymbolTableBuilder.MyIntType)) {
            error("Type error");
            return errorType();
        }
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(TrueLiteral n, SymbolTableBuilder.Context ctx) {
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(FalseLiteral n, SymbolTableBuilder.Context ctx) {
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(IntegerLiteral n, SymbolTableBuilder.Context ctx) {
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(ArrayAssignmentStatement n, SymbolTableBuilder.Context ctx) {
        String vname = n.f0.f0.toString();
        SymbolTableBuilder.MyType arr = lookupVar(ctx, vname);
        if (isSymbolNotFoundType(arr) || isErrorType(arr)) return arr;
        SymbolTableBuilder.MyType idx = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(idx) || isErrorType(idx)) return idx;
        SymbolTableBuilder.MyType rhs = n.f5.accept(this, ctx);
        if (isSymbolNotFoundType(rhs) || isErrorType(rhs)) return rhs;

        if (!(arr instanceof SymbolTableBuilder.MyIntArrayType)) { error("Type error"); return errorType(); }
        if (!(idx instanceof SymbolTableBuilder.MyIntType)) { error("Type error"); return errorType(); }
        if (!(rhs instanceof SymbolTableBuilder.MyIntType)) { error("Type error"); return errorType(); }
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(IfthenStatement n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType cond = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(cond) || isErrorType(cond)) return cond;
        if (!(cond instanceof SymbolTableBuilder.MyBooleanType)) { error("Type error"); return errorType(); }
        n.f4.accept(this, ctx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(IfthenElseStatement n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType cond = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(cond) || isErrorType(cond)) return cond;
        if (!(cond instanceof SymbolTableBuilder.MyBooleanType)) { error("Type error"); return errorType(); }
        n.f4.accept(this, ctx);
        n.f6.accept(this, ctx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(WhileStatement n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType cond = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(cond) || isErrorType(cond)) return cond;
        if (!(cond instanceof SymbolTableBuilder.MyBooleanType)) { error("Type error"); return errorType(); }
        n.f4.accept(this, ctx);
        return null;
    }

    @Override
    public SymbolTableBuilder.MyType visit(AddExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        if (!(a instanceof SymbolTableBuilder.MyIntType) || !(b instanceof SymbolTableBuilder.MyIntType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(MinusExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        if (!(a instanceof SymbolTableBuilder.MyIntType) || !(b instanceof SymbolTableBuilder.MyIntType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(TimesExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType left = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType right = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(left) || isSymbolNotFoundType(right) || isErrorType(left) || isErrorType(right)) return errorType();
        if (!(left instanceof SymbolTableBuilder.MyIntType) || !(right instanceof SymbolTableBuilder.MyIntType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(DivExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        if (!(a instanceof SymbolTableBuilder.MyIntType) || !(b instanceof SymbolTableBuilder.MyIntType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(CompareExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        if (!(a instanceof SymbolTableBuilder.MyIntType) || !(b instanceof SymbolTableBuilder.MyIntType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(AndExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        if (!(a instanceof SymbolTableBuilder.MyBooleanType) || !(b instanceof SymbolTableBuilder.MyBooleanType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(OrExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        if (!(a instanceof SymbolTableBuilder.MyBooleanType) || !(b instanceof SymbolTableBuilder.MyBooleanType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(neqExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType a = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType b = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(a) || isSymbolNotFoundType(b) || isErrorType(a) || isErrorType(b)) return errorType();
        boolean aBool = a instanceof SymbolTableBuilder.MyBooleanType;
        boolean bBool = b instanceof SymbolTableBuilder.MyBooleanType;
        boolean aInt = a instanceof SymbolTableBuilder.MyIntType;
        boolean bInt = b instanceof SymbolTableBuilder.MyIntType;
        if (!((aBool && bBool) || (aInt && bInt))) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(NotExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType t = n.f1.accept(this, ctx);
        if (isSymbolNotFoundType(t) || isErrorType(t)) return t;
        if (!(t instanceof SymbolTableBuilder.MyBooleanType)) {
            error("Type error");
            return errorType();
        }
        return new SymbolTableBuilder.MyBooleanType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(Identifier n, SymbolTableBuilder.Context ctx) {
        String name = n.f0.toString();

        if (ctx.inType) {
            return resolveTypeName(name);
        } else {
            return lookupVar(ctx, name);
        }
    }

    @Override
    public SymbolTableBuilder.MyType visit(ThisExpression n, SymbolTableBuilder.Context ctx) {
        if (ctx.currentClass() == null) {
            error("Symbol not found");
            return symbolNotFoundType();
        }
        return new SymbolTableBuilder.MyClassType(ctx.currentClass());
    }

    @Override
    public SymbolTableBuilder.MyType visit(ArrayLookup n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType arr = n.f0.accept(this, ctx);
        SymbolTableBuilder.MyType idx = n.f2.accept(this, ctx);
        if (isSymbolNotFoundType(arr) || isSymbolNotFoundType(idx) || isErrorType(arr) || isErrorType(idx)) return errorType();
        if (!(arr instanceof SymbolTableBuilder.MyIntArrayType)) { error("Type error"); return errorType(); }
        if (!(idx instanceof SymbolTableBuilder.MyIntType)) { error("Type error"); return errorType(); }
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(ArrayLength n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType arr = n.f0.accept(this, ctx);
        if (isSymbolNotFoundType(arr) || isErrorType(arr)) return errorType();
        if (!(arr instanceof SymbolTableBuilder.MyIntArrayType)) { error("Type error"); return errorType(); }
        return new SymbolTableBuilder.MyIntType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(AllocationExpression n, SymbolTableBuilder.Context ctx) {
        String className = n.f1.f0.toString();
        if (!symtab.hasClass(className)) {
            error("Symbol not found");
            return symbolNotFoundType();
        }
        return new SymbolTableBuilder.MyClassType(className);
    }

    @Override
    public SymbolTableBuilder.MyType visit(ArrayAllocationExpression n, SymbolTableBuilder.Context ctx) {
        SymbolTableBuilder.MyType size = n.f3.accept(this, ctx);
        if (isSymbolNotFoundType(size) || isErrorType(size)) return size;
        if (!(size instanceof SymbolTableBuilder.MyIntType)) { error("Type error"); return errorType(); }
        return new SymbolTableBuilder.MyIntArrayType();
    }

    @Override
    public SymbolTableBuilder.MyType visit(BracketExpression n, SymbolTableBuilder.Context ctx) {
        return n.f1.accept(this, ctx);
    }
}