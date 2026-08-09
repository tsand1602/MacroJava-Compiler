import syntaxtree.Goal;

public class P6 {
   public static void main(String [] args) {
      try {
        MiniRAParser parser = new MiniRAParser(System.in);
        Goal root = parser.Goal();
        MiniRAtoMIPS translator = new MiniRAtoMIPS();
        root.accept(translator, null);
      } catch (ParseException e) {
         System.out.println(e.toString());
      }
   }
} 