
import syntaxtree.*;

class P2 {
    public static void main(String[] args) {
        try {
            MiniJavaParser parser = new MiniJavaParser(System.in);
            Goal root = parser.Goal();

            SymbolTableBuilder stb = new SymbolTableBuilder();
            root.accept(stb, null);
            if (stb.hadError()) {
                System.out.println(stb.getErrorType());
                return;
            }

            TypeChecker tc = new TypeChecker(stb.getSymbolTable());
            root.accept(tc, new SymbolTableBuilder.Context(stb.getSymbolTable(), null, null));

            if (SymbolTableBuilder.hasError) {
                System.out.println(SymbolTableBuilder.errorMessage);
                return;
            }

            System.out.println("Program type checked successfully");
        } catch (ParseException e) {
            System.out.println("Type error");
        } catch (Exception e) {
            System.out.println("Type error");
        }
    }
}