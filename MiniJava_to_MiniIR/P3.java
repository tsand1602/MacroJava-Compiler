import syntaxtree.*;
import java.text.ParseException;

public class P3 {
    public static void main(String[] args) throws ParseException {
        try {
            Node root = new MiniJavaParser(System.in).Goal();
            SymbolTableVisitor stVisitor = new SymbolTableVisitor();
            root.accept(stVisitor, null);
            SymbolTableVisitor.SymbolTable st = stVisitor.getSymbolTable();
            IRGeneratorVisitor irGeneratorVisitor = new IRGeneratorVisitor(st, stVisitor.lambdaClassMap);
            IRGeneratorVisitor.IRInfo res = root.accept(irGeneratorVisitor, null);
            System.out.println(res.code);
        } catch (Exception e) {
            System.out.println(e.toString());
        }
    }
}