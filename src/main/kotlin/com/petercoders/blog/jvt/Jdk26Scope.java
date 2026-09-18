import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
public class Jdk26Scope {
    static String fetch(String u) throws Exception { Thread.sleep(50); if (u.startsWith("bad")) throw new IllegalStateException(u); return u; }
    public static void main(String[] a) throws Exception {
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(() -> fetch("ok-1"));
            scope.fork(() -> fetch("bad-2"));
            List<String> rs = scope.join();
            System.out.println(rs);
        } catch (StructuredTaskScope.FailedException e) { System.out.println("failed: " + e.getCause()); }
    }
}
