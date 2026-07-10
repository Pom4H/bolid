package com.thebutton.ble

import android.app.Application
import com.thebutton.ble.ble.BleClient

class DplsApplication : Application() {
    val bleClient: BleClient by lazy { BleClient(this) }
}
