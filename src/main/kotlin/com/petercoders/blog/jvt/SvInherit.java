import java.util.concurrent.StructuredTaskScope;
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
// JDK 26, StructuredTaskScope 때문에 --enable-preview 필요.
//   J26=$(/usr/libexec/java_home -v 26)
//   $J26/bin/java --enable-preview --source 26 SvInherit.java
// 출력은 runs/jvt-e7-scopedvalue-inherit.log 에 있다:
//   child: REQ=req-42 TL=null VirtualThread[#32]/runnable@ForkJoinPool-1-worker-1
//   after: REQ.isBound=false TL=tl-main
// TL 이 null 인 것은 ThreadLocal 이 InheritableThreadLocal 이 아니고 초기값도 없기 때문이다.
