public class SvInherit {
    static final ScopedValue<String> REQ = ScopedValue.newInstance();
    static final ThreadLocal<String> TL = new ThreadLocal<>();
    public static void main(String[] args) throws Exception {
        TL.set("tl-main");
        ScopedValue.where(REQ, "req-42").run(() -> {
            try (var scope = StructuredTaskScope.open()) {
                scope.fork(() -> { System.out.println("child: REQ=" + REQ.get() + " TL=" + TL.get() + " " + Thread.currentThread()); return null; });
                scope.join();
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        System.out.println("after: REQ.isBound=" + REQ.isBound() + " TL=" + TL.get());
    }
}
// JDK 26, StructuredTaskScope 때문에 --enable-preview 필요. 이 실험은 로컬에서 실행하지 않았다(미실행).
// $J26/bin/java --enable-preview --source 26 SvInherit.java
