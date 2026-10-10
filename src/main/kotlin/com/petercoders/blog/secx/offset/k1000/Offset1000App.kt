// [S4d] offset = 1000 앱. OffsetProbe.kt 참고.
package com.petercoders.blog.secx.offset.k1000

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.secx.offset.common"])
@EnableMethodSecurity(offset = 1000)
@Profile("s4d")
class Offset1000App
