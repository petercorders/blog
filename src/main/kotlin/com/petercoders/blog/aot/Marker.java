package com.petercoders.blog.aot;

/**
 * e10b 전용. AOT 캐시를 만든 뒤 이 클래스 하나를 앱 jar 에 `jar uf` 로 추가해
 * "클래스패스의 jar 내용이 바뀌면 캐시가 무효가 되는가"를 본다. 실행 코드는 없다.
 */
public final class Marker {
    private Marker() {}
}
