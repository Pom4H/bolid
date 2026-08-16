package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.PowerSource

val DplsMode.title: String
    get() = when (this) {
        DplsMode.NORMAL -> "Норма"
        DplsMode.OPEN_T -> "Обрыв +Т"
        DplsMode.OPEN_MAIN -> "Обрыв магистрали"
        DplsMode.SHORT_1 -> "КЗ +1"
        DplsMode.SHORT_2 -> "КЗ +2"
        DplsMode.SHORT_T -> "КЗ +Т"
    }

val DplsMode.portHint: String
    get() = when (this) {
        DplsMode.NORMAL -> "Штатное прохождение линии"
        DplsMode.OPEN_T -> "Ответвление +Т"
        DplsMode.OPEN_MAIN -> "Магистраль +1 ↔ +2"
        DplsMode.SHORT_1 -> "Порт +1"
        DplsMode.SHORT_2 -> "Порт +2"
        DplsMode.SHORT_T -> "Ответвление +Т"
    }

val DplsMode.controllerEffect: String
    get() = when (this) {
        DplsMode.NORMAL -> ""
        DplsMode.OPEN_T -> "КДЛ: потеря устройств ответвления"
        DplsMode.OPEN_MAIN -> "КДЛ: «Нет связи» с устройствами за разрывом"
        DplsMode.SHORT_1, DplsMode.SHORT_2, DplsMode.SHORT_T -> "КДЛ: «Короткое замыкание ДПЛС»"
    }

val PowerSource.title: String
    get() = when (this) {
        PowerSource.DPLS -> "ДПЛС"
        PowerSource.RESERVE -> "Резерв"
    }
