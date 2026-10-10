/*
 * e16 (4) can_generate_all_class_hook_events 권한을 요청하는 최소 native(JVMTI) agent.
 *   옵션 없음 : 권한만 요청한다. 이벤트를 켜거나 클래스를 바꾸지 않는다.
 *   =cflh     : 권한 + ClassFileLoadHook 이벤트까지 켠다(콜백은 아무것도 바꾸지 않고 횟수만 센다).
 * 빌드: clang -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin -o libe16native.dylib e16_native.c
 */
#include <jvmti.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static volatile long hook_calls = 0;

static void JNICALL on_cflh(jvmtiEnv *env, JNIEnv *jni, jclass redefined, jobject loader, const char *name,
                            jobject pd, jint len, const unsigned char *data, jint *new_len, unsigned char **new_data) {
    hook_calls++;
}

static void report(void) {
    printf("[e16] native agent: ClassFileLoadHook calls = %ld\n", hook_calls);
    fflush(stdout);
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti = NULL;
    if ((*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        fprintf(stderr, "[e16] GetEnv failed\n");
        return JNI_ERR;
    }
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_generate_all_class_hook_events = 1;
    jvmtiError err = (*jvmti)->AddCapabilities(jvmti, &caps);
    printf("[e16] native agent loaded: AddCapabilities(can_generate_all_class_hook_events) = %d\n", (int)err);
    if (options != NULL && strcmp(options, "cflh") == 0) {
        jvmtiEventCallbacks cb;
        memset(&cb, 0, sizeof(cb));
        cb.ClassFileLoadHook = &on_cflh;
        jvmtiError e1 = (*jvmti)->SetEventCallbacks(jvmti, &cb, sizeof(cb));
        jvmtiError e2 = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
        printf("[e16] native agent: ClassFileLoadHook enabled (SetEventCallbacks=%d, SetEventNotificationMode=%d)\n", (int)e1, (int)e2);
        atexit(report);
    }
    fflush(stdout);
    return JNI_OK;
}
