using System.Text;

namespace TestDPLS.Models;

public enum DplsMode
{
    Normal = 0,
    OpenT = 1,
    OpenMain = 2,
    Short1 = 3,
    Short2 = 4,
    ShortT = 5,
}

public static class DplsModeInfo
{
    public static string Title(DplsMode mode) => mode switch
    {
        DplsMode.Normal => "Норма",
        DplsMode.OpenT => "Обрыв +Т",
        DplsMode.OpenMain => "Обрыв магистрали",
        DplsMode.Short1 => "КЗ +1",
        DplsMode.Short2 => "КЗ +2",
        DplsMode.ShortT => "КЗ +Т",
        _ => $"код {(int)mode}",
    };

    public static bool Dangerous(DplsMode mode) => mode != DplsMode.Normal;

    public static string PortHint(DplsMode mode) => mode switch
    {
        DplsMode.Normal => "Штатное прохождение линии",
        DplsMode.OpenT => "Ответвление +Т",
        DplsMode.OpenMain => "Магистраль +1 ↔ +2",
        DplsMode.Short1 => "Порт +1",
        DplsMode.Short2 => "Порт +2",
        DplsMode.ShortT => "Ответвление +Т",
        _ => "",
    };

    public static string ControllerEffect(DplsMode mode) => mode switch
    {
        DplsMode.OpenT => "КДЛ: потеря устройств ответвления",
        DplsMode.OpenMain => "КДЛ: «Нет связи» с устройствами за разрывом",
        DplsMode.Short1 or DplsMode.Short2 or DplsMode.ShortT => "КДЛ: «Короткое замыкание ДПЛС»",
        _ => "",
    };

    public static DplsMode? FromWire(int value) =>
        Enum.IsDefined(typeof(DplsMode), value) ? (DplsMode)value : null;

    public static IEnumerable<DplsMode> DangerousModes =>
        Enum.GetValues<DplsMode>().Where(Dangerous);
}

public enum PowerSource
{
    Dpls = 0,
    Reserve = 1,
}

public static class PowerSourceInfo
{
    public static string Title(PowerSource source) => source == PowerSource.Dpls ? "ДПЛС" : "Резерв";
}

public readonly record struct DplsEventTime(string? DateLabel, string Time, string Full);

public static class DplsEventFormatting
{
    public static DplsEventTime Format(EventRecord e, uint currentRunFirstSeq, long? bootEpochSec)
    {
        if (e.Sequence >= currentRunFirstSeq && bootEpochSec is long boot)
        {
            var date = DateTimeOffset.FromUnixTimeSeconds(boot + e.TimestampSeconds).LocalDateTime;
            var time = date.ToString("HH:mm:ss");
            var dateLabel = date.ToString("dd.MM.yyyy");
            return new DplsEventTime(dateLabel, time, $"{dateLabel} {time}");
        }

        var t = e.TimestampSeconds;
        var rel = $"+{t / 3600:D2}:{(t % 3600) / 60:D2}:{t % 60:D2}";
        return new DplsEventTime(null, rel, $"{rel} (от запуска)");
    }

    public static string Title(int type, int parameter) => type switch
    {
        1 => "Запуск устройства",
        2 => "BLE подключение",
        3 => "BLE отключение",
        4 => "Успешный вход",
        5 => $"Ошибка входа · попытка {parameter}",
        6 => "Вход заблокирован",
        7 => $"Режим: {DplsModeInfo.Title(DplsModeInfo.FromWire(parameter) ?? (DplsMode)parameter)}",
        8 => AutoReturnTitle(parameter),
        9 => "Идентификация начата",
        10 => "Идентификация остановлена",
        11 => "Пароль установлен",
        12 => $"Питание: {(parameter == 0 ? "от ДПЛС" : "от резерва")}",
        13 => $"Резерв: {(parameter == 0 ? "норма" : "низкий заряд")}",
        14 => $"Автоизоляция КЗ: {(parameter == 0 ? "снята" : "активна")}",
        _ => $"Событие {type} · {parameter}",
    };

    private static string AutoReturnTitle(int reason) => reason switch
    {
        0 => "Автовозврат в «Норма» (команда оператора)",
        1 => "Автовозврат в «Норма» (таймер)",
        2 => "Автовозврат в «Норма» (таймаут сессии)",
        3 => "Автовозврат в «Норма» (отключение BLE)",
        4 => "Автовозврат в «Норма» (низкий резерв)",
        5 => "Автовозврат в «Норма» (ошибка)",
        6 => "Автовозврат в «Норма» (перезапуск)",
        7 => "Автовозврат в «Норма» (автоизоляция КЗ)",
        _ => "Автовозврат в «Норма»",
    };
}

public enum ConnectionPhase
{
    Idle,
    Scanning,
    Connecting,
    Pairing,
    NegotiatingMtu,
    Discovering,
    Subscribing,
    Authenticating,
    Synchronizing,
    Ready,
    Reconnecting,
    Error,
}

public sealed class DiscoveredDevice
{
    /// <summary>Bluetooth address string (Windows BluetoothAddress as hex).</summary>
    public required string Address { get; init; }
    public required string AdvertisedName { get; init; }
    public string? UserName { get; set; }
    public uint? DeviceId { get; set; }
    public int Rssi { get; set; }
}

public sealed class DeviceState
{
    public DplsMode Mode { get; init; }
    public int VoltageMv { get; init; }
    public PowerSource PowerSource { get; init; }
    public bool ReserveLow { get; init; }
    public bool RealShort { get; init; }
    public int AutomaticReturnSeconds { get; init; }
    public uint UptimeSeconds { get; init; }
    public uint Revision { get; init; }
    public long ReceivedAtMillis { get; init; }
    public bool LineVoltageValid { get; init; } = true;
    public bool ReserveValid { get; init; } = true;
    public bool PowerValid { get; init; } = true;
    public bool AutoIsoValid { get; init; } = true;
    public bool AdcCalibrated { get; init; }
}

public readonly record struct EventRecord(uint Sequence, uint TimestampSeconds, int Type, int Parameter);

public sealed class DeviceInfo
{
    public required uint DeviceId { get; init; }
    public required int ProtocolVersion { get; init; }
    public required string FirmwareVersion { get; init; }
    public required int HardwareRevision { get; init; }
    public required bool AdcPresent { get; init; }
    public required bool HardwareReadback { get; init; }
    public required bool AdcCalibrated { get; init; }
    public required string UserName { get; init; }
    public string ShortId => $"DPLS-{DeviceId:X8}";
}

public enum SettingsOp
{
    None,
    InProgress,
    Done,
    Failed,
}

public static class Utf8Util
{
    public static byte[] Truncate(string value, int maxBytes)
    {
        var result = new List<byte>(Math.Min(maxBytes, value.Length * 3));
        Span<byte> buf = stackalloc byte[4];
        foreach (var rune in value.EnumerateRunes())
        {
            var n = Encoding.UTF8.GetBytes(rune.ToString(), buf);
            if (result.Count + n > maxBytes) break;
            for (var i = 0; i < n; i++) result.Add(buf[i]);
        }
        return result.ToArray();
    }
}

public sealed class DplsUiState
{
    public ConnectionPhase Phase { get; set; } = ConnectionPhase.Idle;
    public string StatusText { get; set; } = "Готово к поиску";
    public List<DiscoveredDevice> Devices { get; set; } = [];
    public DiscoveredDevice? SelectedDevice { get; set; }
    public bool Initialized { get; set; }
    public bool CredentialsReady { get; set; }
    public bool Authenticated { get; set; }
    public DeviceState? State { get; set; }
    public DplsMode? PendingMode { get; set; }
    public bool CommandInProgress { get; set; }
    public bool StaleState { get; set; }
    public long? LastAckMillis { get; set; }
    public List<EventRecord> EventLog { get; set; } = [];
    public long? DeviceBootEpochSeconds { get; set; }
    public float? LogProgress { get; set; }
    public bool IdentifyActive { get; set; }
    public bool IdentifyLedLive { get; set; }
    public string SetupName { get; set; } = "";
    public string SetupPassword { get; set; } = "";
    public string SetupRepeatPassword { get; set; } = "";
    public bool AwaitingUserPassword { get; set; }
    public DeviceInfo? DeviceInfo { get; set; }
    public SettingsOp SettingsOp { get; set; } = SettingsOp.None;
    public string? SettingsError { get; set; }
    public string? Error { get; set; }

    public bool ControlsEnabled =>
        Phase == ConnectionPhase.Ready && Authenticated && !CommandInProgress;

    public bool SetupFormReady =>
        CredentialsReady &&
        SetupPassword.Length >= 8 &&
        (Initialized || (SetupRepeatPassword == SetupPassword &&
                         !string.IsNullOrWhiteSpace(SetupName)));
}
