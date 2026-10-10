// SPRING-INTERNALS 2편 [S9] 전용. Gradle 이 컴파일하지 않는다(build.gradle.kts 에서 제외).
// -parameters 없이 javac 로 따로 컴파일해 MethodParameters 속성이 없는 클래스를 만든다(명령은 runs/s9.log 머리).
package com.petercoders.blog.secx.s9;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;

public class S9Target {

    @PreAuthorize("#id == authentication.name")
    public String byId(String id) {
        return "body-ran";
    }

    @PreAuthorize("#id == authentication.name")
    public String byIdWithP(@P("id") String id) {
        return "body-ran";
    }
}
