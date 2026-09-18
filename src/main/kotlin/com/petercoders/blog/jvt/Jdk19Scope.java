import jdk.incubator.concurrent.StructuredTaskScope;
import java.util.concurrent.*;
public class Jdk19Scope {
    static String fetch(String u) throws Exception { Thread.sleep(50); if (u.startsWith("bad")) throw new IllegalStateException(u); return u; }
    public static void main(String[] a) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Future<String> f1 = scope.fork(() -> fetch("ok-1"));
            Future<String> f2 = scope.fork(() -> fetch("bad-2"));
            scope.join().throwIfFailed();
            System.out.println(f1.resultNow() + f2.resultNow());
        } catch (ExecutionException e) { System.out.println("failed: " + e.getCause()); }
    }
}
