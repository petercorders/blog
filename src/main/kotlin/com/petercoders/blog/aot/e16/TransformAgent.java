// e16 (2) 앱 클래스 1개의 바이트를 실제로 바꾸는 Java agent.
// com/petercoders/blog/BlogApplication 에 public static int __e16 필드를 하나 더한다(ClassFile API, JDK 24+).
// BlogApplication 은 @SpringBootApplication 이라 Spring 이 CGLIB 하위 클래스를 만든다 — 하위 타입 제외도 같이 보인다.
package e16;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ConstantDescs;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class TransformAgent {
    static final String TARGET = "com/petercoders/blog/BlogApplication";

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[e16] transform agent premain");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> cls, ProtectionDomain pd, byte[] bytes) {
                if (!TARGET.equals(name)) return null;
                ClassFile cf = ClassFile.of();
                byte[] out = cf.transformClass(cf.parse(bytes),
                        ClassTransform.endHandler(b -> b.withField("__e16", ConstantDescs.CD_int,
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
                System.out.println("[e16] transformed " + name + " " + bytes.length + " -> " + out.length + " bytes");
                return out;
            }
        });
    }
}
