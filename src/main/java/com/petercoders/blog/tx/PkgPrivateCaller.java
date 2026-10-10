package com.petercoders.blog.tx;

import org.springframework.stereotype.Service;

/** [e2f] 같은 패키지의 다른 Bean. 주입받은 것은 PkgPrivateProbe 의 CGLIB Proxy 다. */
@Service
public class PkgPrivateCaller {

    private final PkgPrivateProbe probe;

    public PkgPrivateCaller(PkgPrivateProbe probe) {
        this.probe = probe;
    }

    public String call() {
        return probe.pkgTx();
    }

    public String proxyClass() {
        return probe.getClass().getName();
    }
}
