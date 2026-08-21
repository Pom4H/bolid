package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.runtime.ConnectionEvent
import ru.bolid.testdpls.core.runtime.ConnectionMachine
import ru.bolid.testdpls.core.runtime.DeviceSession

/**
 * Mutable shell around the pure connection reducer.
 *
 * This is the only mutable owner of [DeviceSession]. Product code may change
 * lifecycle state only by dispatching semantic [ConnectionEvent] facts.
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
