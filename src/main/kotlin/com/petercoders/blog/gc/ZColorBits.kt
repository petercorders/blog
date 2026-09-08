package com.petercoders.blog.gc

import java.lang.reflect.Field

/** ZGC 컬러 포인터의 하위 16비트를 배리어 없이 읽어 사이클마다 어떻게 바뀌는지 본다. */
private class Slot { @JvmField var ref: Any? = null }

private val unsafe: sun.misc.Unsafe = run {
    val f: Field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    f.isAccessible = true
    f.get(null) as sun.misc.Unsafe
}

/** 참조 필드를 정수로 읽는다. 정수 읽기라 로드 배리어를 타지 않는다.
 *
 * raw>>16 은 주소가 아니다. 언컬러 시프트가 사이클마다 13~16 사이에서 정해지므로
 * (ZPointerLoadShiftTable) 이 열은 "상위 비트가 움직였다" 정도로만 읽어야 한다. */
private fun dump(h: Slot, off: Long, label: String) {
    val raw = unsafe.getLong(h, off)
    val b = java.lang.Long.toBinaryString(raw and 0xFFFF).padStart(16, '0')
    println("%-30s raw=0x%016x  RRRR=%s MM=%s mm=%s FF=%s rr=%s 0000=%s  raw>>16=0x%x"
        .format(label, raw, b.substring(0, 4), b.substring(4, 6), b.substring(6, 8),
            b.substring(8, 10), b.substring(10, 12), b.substring(12, 16), raw ushr 16))
}

fun main() {
    val off = unsafe.objectFieldOffset(Slot::class.java.getDeclaredField("ref"))
    val h = Slot()
    h.ref = Any()
    println("field offset = $off")
    dump(h, off, "store 직후")
    for (i in 1..3) {
        System.gc()                                  // ZGC: Major Collection, good 색이 바뀐다
        dump(h, off, "gc#$i 후, 자바 코드로 읽기 전")
        val o = h.ref                                // 로드 배리어 → 느린 경로면 self_heal
        dump(h, off, "gc#$i 후, h.ref 한 번 읽은 뒤")
        checkNotNull(o)
    }
}
