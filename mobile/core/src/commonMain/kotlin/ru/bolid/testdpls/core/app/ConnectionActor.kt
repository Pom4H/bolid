package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.runtime.ConnectionEvent
import ru.bolid.testdpls.core.runtime.ConnectionMachine
import ru.bolid.testdpls.core.runtime.DeviceSession

/**
 * Единственный mutable owner DeviceSession.
 *
 * Product-код меняет lifecycle только через semantic ConnectionEvent, а сам граф
 * переходов остаётся чистым и тестируемым в ConnectionMachine.
 */
internal class ConnectionActor(
    initial: DeviceSession = DeviceSession.Offline,
) {
    var state: DeviceSession = initial
        private set

    fun dispatch(event: ConnectionEvent) {
        state = ConnectionMachine.reduce(state, event)
    }
}
