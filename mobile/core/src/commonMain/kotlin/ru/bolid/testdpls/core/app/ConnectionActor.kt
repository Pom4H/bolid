package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.runtime.ConnectionEffect
import ru.bolid.testdpls.core.runtime.ConnectionEvent
import ru.bolid.testdpls.core.runtime.ConnectionMachine
import ru.bolid.testdpls.core.runtime.DeviceSession

/**
 * Mutable shell around the pure reducer. DplsClient is allowed to dispatch facts;
 * it is not allowed to assign lifecycle state directly.
 */
internal class ConnectionActor(
    initial: DeviceSession = DeviceSession.Offline,
) {
    var state: DeviceSession = initial
        private set

    fun dispatch(event: ConnectionEvent): List<ConnectionEffect> {
        val transition = ConnectionMachine.reduce(state, event)
        state = transition.state
        return transition.effects
    }
}
