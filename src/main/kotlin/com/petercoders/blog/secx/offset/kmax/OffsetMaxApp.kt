// [S4d] offset = Int.MAX_VALUE 앱. OffsetProbe.kt 참고.
package com.petercoders.blog.secx.offset.kmax

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.secx.offset.common"])
@EnableMethodSecurity(offset = Int.MAX_VALUE)
@Profile("s4d")
class OffsetMaxApp
