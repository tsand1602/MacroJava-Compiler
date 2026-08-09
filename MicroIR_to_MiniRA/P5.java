import syntaxtree.Goal;

public class P5 {
    public static void main(String[] args) {
        try {
            Goal root = new microIRParser(System.in).Goal();
            CFGBuilderVisitor cfgBuilder = new CFGBuilderVisitor();
            root.accept(cfgBuilder, null);
            LivenessAndAllocator allocator = new LivenessAndAllocator();
            allocator.run(cfgBuilder.functions);
            MiniRAEmitter emitter = new MiniRAEmitter(allocator.allocations);
            root.accept(emitter, null);
            System.out.println(emitter.getOutput());
        } catch (ParseException e) {
            System.out.println(e.toString());
        }
    }
}