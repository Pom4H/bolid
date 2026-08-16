package ru.bolid.testdpls.core.session

/**
 * Wire sequencing only. Protocol/auth state belongs exclusively to DeviceSession.
 * Frame.sequence is the only transaction id in protocol v2.
 */
class FrameSequencer {
    private var next: Int = 1

    fun next(): Int = next.also { next = (it + 1) and 0xffff }

    fun reset() {
        next = 1
    }
}
