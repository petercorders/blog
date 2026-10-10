package com.petercoders.blog.tx;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [e2f] package-private @Transactional 메서드. Kotlin 에는 package-private 가 없어 Java 로 쓴다.
 * 6.0+ 등록 빈은 publicMethodsOnly=false 라 속성이 붙고, CGLIB Proxy 클래스가 같은 패키지·같은 ClassLoader 에
 * 생기므로 override 할 수 있다. 같은 패키지의 다른 Bean(PkgPrivateCaller)에서 부른다.
 */
@Service
public class PkgPrivateProbe {

    @Transactional
    String pkgTx() {
        return "active=" + TransactionSynchronizationManager.isActualTransactionActive()
                + " name=" + TransactionSynchronizationManager.getCurrentTransactionName();
    }
}
