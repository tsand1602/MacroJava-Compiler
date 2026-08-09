import syntaxtree.*;

public class P4 {
    public static void main(String[] args) {
        try {
            MiniIRParser parser = new MiniIRParser(System.in);
            Goal root = parser.Goal();
            MiniToMicroTranslator v = new MiniToMicroTranslator();
            root.accept(v, null);
            System.out.print(v.output());
        } catch (ParseException e) {
            System.out.println(e.toString());
        }
    }
}
