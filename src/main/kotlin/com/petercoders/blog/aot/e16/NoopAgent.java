// e16 (1) premain 만 있는 Java agent. 클래스를 바꾸지 않는다.
// Gradle 은 src/main/kotlin 아래 .java 를 컴파일하지 않는다. e16.sh 가 javac/jar 로 따로 만든다.
package e16;

import java.lang.instrument.Instrumentation;

public final class NoopAgent {
    public static void premain(String args, Instrumentation inst) {
        System.out.println("[e16] noop agent premain");
    }
}
