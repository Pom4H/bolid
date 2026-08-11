using System.Text;
using TestDPLS.Crypto;
using TestDPLS.Models;
using TestDPLS.Protocol;

namespace TestDPLS.Session;

/// <summary>
/// Application-layer Test-DPLS session (auth, modes, journal, settings).
/// Transport (Windows BLE) is injected via <see cref="IDplsTransport"/>.
/// </summary>
public sealed class DplsSession
{
    public const ushort ManufacturerId = 0x0B01;
    public static readonly Guid ServiceUuid = Guid.Parse("7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001");
    public static readonly Guid RxUuid = Guid.Parse("7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001");
    public static readonly Guid TxUuid = Guid.Parse("7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001");

    private const int MaxLogEvents = 200;

    private readonly IDplsTransport _transport;
    private readonly object _gate = new();
    private readonly SynchronizationContext? _sync;

    private ushort _sequence = 1;
    private uint _commandId = 1;
    private uint _sessionId;
    private byte[] _sessionToken = new byte[8];
    private byte[] _clientNonce = new byte[16];
    private byte[] _deviceNonce = new byte[16];
    private byte[] _authSalt = new byte[16];
    private byte[]? _cachedVerifier;
    private string? _pendingSetupName;
    private bool _initialized;
    private bool _reachedReady;
    private bool _awaitingDeviceInfo;
    private bool _legacyFirmware;
    private bool _identifyAfterConnect;
    private bool _pendingIdentifyAck;
    private bool _logLoadPending;
    private bool _logInfoReceived;
    private int _logExpectedBytes;
    private int _logExpectedEvents;
    private int _logReceivedEvents;
    private byte[] _logBytes = [];
    private bool[] _logChunkReceived = [];
    private readonly List<(int Index, byte[] Data)> _logPendingChunks = [];
    private int? _pendingLogAckIndex;
    private PendingSettings? _pendingSettings;
    private CancellationTokenSource? _preAuthKeepAliveCts;
    private CancellationTokenSource? _keepAliveCts;
    private CancellationTokenSource? _stateRefreshCts;
    private CancellationTokenSource? _logLoadTimeoutCts;
    private CancellationTokenSource? _logAckCts;
    private CancellationTokenSource? _settingsTimeoutCts;
    private CancellationTokenSource? _commandTimeoutCts;

    private enum PendingSettingsKind { Name, Password }

    private sealed record PendingSettings(PendingSettingsKind Kind, uint CommandId, byte[]? NewVerifier);

    public DplsUiState Ui { get; private set; } = new();
    public event Action? UiChanged;
    public int NegotiatedWriteLimit { get; set; } = 20;
    public bool IsLinked { get; private set; }

    public DplsSession(IDplsTransport transport, SynchronizationContext? sync = null)
    {
        _transport = transport;
        _sync = sync;
    }

    public void NotifyLinked()
    {
        IsLinked = true;
    }

    public void NotifyUnlinked(bool scheduleReconnectHint)
    {
        IsLinked = false;
        CancelTimers(keepReconnectRelevant: true);
        ResetWriteAwareState();
        if (Ui.LogProgress != null)
        {
            _logLoadPending = true;
            Ui.LogProgress = null;
            Ui.Error = null;
            Ui.StatusText = "Восстановление связи…";
        }
        Ui.Authenticated = false;
        Ui.CredentialsReady = _cachedVerifier != null;
        Ui.StaleState = Ui.State != null;
        RaiseUi();
    }

    public void OnGattReady(bool startIdentify)
    {
        _clientNonce = DplsCrypto.RandomBytes(16);
        if (startIdentify || _identifyAfterConnect)
        {
            _identifyAfterConnect = false;
            _pendingIdentifyAck = true;
            Ui.Phase = ConnectionPhase.Pairing;
            Ui.StatusText = "Подтвердите сопряжение…";
            RaiseUi();
            Send(DplsProtocol.MessageType.IdentifyStart);
        }
        else
        {
            Ui.Phase = ConnectionPhase.Pairing;
            Ui.StatusText = "Подключение…";
            RaiseUi();
            Send(DplsProtocol.MessageType.Hello, _clientNonce);
        }
    }

    public void OnWriteCompleted(bool success)
    {
        if (!success) return;
        if (_pendingIdentifyAck)
        {
            _pendingIdentifyAck = false;
            Ui.IdentifyLedLive = true;
            if (Ui.Phase == ConnectionPhase.Pairing)
            {
                Ui.Phase = ConnectionPhase.Authenticating;
                Ui.StatusText = "Показать на объекте…";
            }
            RaiseUi();
        }
        else if (Ui.Phase == ConnectionPhase.Pairing)
        {
            Ui.Phase = ConnectionPhase.Authenticating;
            Ui.StatusText = "Подключение…";
            RaiseUi();
        }
        FlushLogAck();
    }

    public void BeginIdentifyFlow()
    {
        _identifyAfterConnect = true;
        _pendingIdentifyAck = false;
        Ui.IdentifyActive = true;
        Ui.IdentifyLedLive = false;
        RaiseUi();
    }

    public void StopIdentify()
    {
        _identifyAfterConnect = false;
        _pendingIdentifyAck = false;
        Ui.IdentifyActive = false;
        Ui.IdentifyLedLive = false;
        if (IsLinked)
            SendPriority(DplsProtocol.MessageType.IdentifyStop);
        RaiseUi();
    }

    public void ConfirmIdentifiedDevice()
    {
        StopIdentify();
        CancelPreAuthKeepAlive();
        if (Ui.CredentialsReady || !IsLinked) return;
        Ui.Phase = ConnectionPhase.Authenticating;
        Ui.StatusText = "Подключение…";
        Ui.IdentifyActive = false;
        Ui.IdentifyLedLive = false;
        Ui.Error = null;
        RaiseUi();
        SendPriority(DplsProtocol.MessageType.Hello, _clientNonce);
    }

    // Field edits must not RaiseUi — mobile/desktop hosts rebuild screens on UiChanged
    // and would steal focus from the password box on every keystroke.
    public void UpdateSetupName(string name) => Ui.SetupName = name;
    public void UpdateSetupPassword(string password) => Ui.SetupPassword = password;
    public void UpdateSetupRepeatPassword(string password) => Ui.SetupRepeatPassword = password;

    public void Authenticate(string password)
    {
        if (password.Length < 8)
        {
            Fail("Пароль должен содержать не менее 8 символов");
            return;
        }
        CancelPreAuthKeepAlive();
        var verifier = DplsCrypto.DeriveVerifier(password, _authSalt);
        _cachedVerifier = verifier;
        SendAuthProof(verifier);
    }

    public void Setup(string deviceName, string password)
    {
        var trimmed = deviceName.Trim();
        if (string.IsNullOrEmpty(trimmed))
        {
            Fail("Введите имя устройства");
            return;
        }
        if (password.Length < 8)
        {
            Fail("Пароль должен содержать не менее 8 символов");
            return;
        }

        var salt = DplsCrypto.RandomBytes(16);
        var verifier = DplsCrypto.DeriveVerifier(password, salt);
        _cachedVerifier = verifier;
        _pendingSetupName = trimmed;
        var name = Utf8Util.Truncate(trimmed, 31);
        var payload = new List<byte>();
        LittleEndian.AppendU32(payload, _sessionId);
        LittleEndian.AppendU8(payload, (byte)name.Length);
        payload.AddRange(name);
        payload.AddRange(salt);
        payload.AddRange(verifier);
        Send(DplsProtocol.MessageType.Setup, payload.ToArray());
    }

    public void RequestMode(DplsMode mode)
    {
        if (!Ui.ControlsEnabled) return;
        Ui.PendingMode = mode;
        RaiseUi();
    }

    public void CancelMode()
    {
        Ui.PendingMode = null;
        RaiseUi();
    }

    public void ConfirmMode()
    {
        if (Ui.PendingMode is not { } mode) return;
        var id = _commandId++;
        var payload = new List<byte>();
        LittleEndian.AppendU32(payload, _sessionId);
        payload.AddRange(_sessionToken);
        LittleEndian.AppendU32(payload, id);
        LittleEndian.AppendU8(payload, (byte)mode);
        Ui.CommandInProgress = true;
        Ui.PendingMode = null;
        Ui.StatusText = "Команда отправлена…";
        RaiseUi();
        UpdateStateRefreshSchedule();
        Send(DplsProtocol.MessageType.ModeSet, payload.ToArray());
        ArmCommandTimeout();
    }

    public void ReturnToNormal()
    {
        Ui.PendingMode = DplsMode.Normal;
        ConfirmMode();
    }

    public void RequestDeviceInfo()
    {
        if (!Ui.Authenticated || !IsLinked) return;
        RequestDeviceInfoInternal();
    }

    public void ClearSettingsOp()
    {
        ClearPendingSettings();
        Ui.SettingsOp = SettingsOp.None;
        Ui.SettingsError = null;
        RaiseUi();
    }

    public void SetDeviceName(string name)
    {
        var trimmed = name.Trim();
        if (string.IsNullOrEmpty(trimmed))
        {
            Ui.SettingsOp = SettingsOp.Failed;
            Ui.SettingsError = "Введите имя устройства";
            RaiseUi();
            return;
        }
        if (!Ui.Authenticated || !IsLinked)
        {
            Ui.SettingsOp = SettingsOp.Failed;
            Ui.SettingsError = "Нет соединения с устройством";
            RaiseUi();
            return;
        }

        var nameBytes = Utf8Util.Truncate(trimmed, 31);
        var id = _commandId++;
        ArmPendingSettings(new PendingSettings(PendingSettingsKind.Name, id, null));
        var payload = new List<byte>();
        LittleEndian.AppendU32(payload, _sessionId);
        payload.AddRange(_sessionToken);
        LittleEndian.AppendU32(payload, id);
        LittleEndian.AppendU8(payload, (byte)nameBytes.Length);
        payload.AddRange(nameBytes);
        Ui.SettingsOp = SettingsOp.InProgress;
        Ui.SettingsError = null;
        RaiseUi();
        Send(DplsProtocol.MessageType.NameSet, payload.ToArray());
    }

    public void ChangePassword(string current, string newPassword)
    {
        if (newPassword.Length < 8)
        {
            Ui.SettingsOp = SettingsOp.Failed;
            Ui.SettingsError = "Пароль должен содержать не менее 8 символов";
            RaiseUi();
            return;
        }
        if (!Ui.Authenticated || !IsLinked)
        {
            Ui.SettingsOp = SettingsOp.Failed;
            Ui.SettingsError = "Нет соединения с устройством";
            RaiseUi();
            return;
        }

        var currentVerifier = DplsCrypto.DeriveVerifier(current, _authSalt);
        if (_cachedVerifier is null || !currentVerifier.AsSpan().SequenceEqual(_cachedVerifier))
        {
            Ui.SettingsOp = SettingsOp.Failed;
            Ui.SettingsError = "Неверный текущий пароль";
            RaiseUi();
            return;
        }

        var newSalt = DplsCrypto.RandomBytes(16);
        var newVerifier = DplsCrypto.DeriveVerifier(newPassword, newSalt);
        var id = _commandId++;
        ArmPendingSettings(new PendingSettings(PendingSettingsKind.Password, id, newVerifier));
        var payload = new List<byte>();
        LittleEndian.AppendU32(payload, _sessionId);
        payload.AddRange(_sessionToken);
        LittleEndian.AppendU32(payload, id);
        payload.AddRange(newSalt);
        payload.AddRange(newVerifier);
        Ui.SettingsOp = SettingsOp.InProgress;
        Ui.SettingsError = null;
        RaiseUi();
        Send(DplsProtocol.MessageType.PasswordSet, payload.ToArray());
    }

    public void LoadEventLog()
    {
        if (Ui.LogProgress != null) return;
        _logLoadPending = true;
        CancelKeepAlive();
        CancelStateRefresh();
        ResetLogTransfer();
        Ui.LogProgress = 0;
        Ui.EventLog = [];
        Ui.Error = null;
        RaiseUi();
        var window = new List<byte>();
        LittleEndian.AppendU16(window, 0);
        SendPriority(DplsProtocol.MessageType.LogStart, LittleEndian.Concat(AuthenticatedPayload(), window.ToArray()), flush: true);
        ArmLogLoadTimeout();
    }

    public void RefreshState()
    {
        if (!Ui.Authenticated || !IsLinked || Ui.LogProgress != null) return;
        Send(DplsProtocol.MessageType.StateGet, AuthenticatedPayload());
        UpdateStateRefreshSchedule();
    }

    public void ResetForDisconnect(bool clearCredentials)
    {
        CancelTimers(keepReconnectRelevant: false);
        ResetLogTransfer();
        ResetWriteAwareState();
        _reachedReady = false;
        _awaitingDeviceInfo = false;
        _legacyFirmware = false;
        _identifyAfterConnect = false;
        _pendingIdentifyAck = false;
        _logLoadPending = false;
        _sessionToken = new byte[8];
        if (clearCredentials) _cachedVerifier = null;
        IsLinked = false;
        Ui = new DplsUiState();
        RaiseUi();
    }

    public void PrepareConnect(DiscoveredDevice? selected)
    {
        _legacyFirmware = false;
        _awaitingDeviceInfo = false;
        Ui.Phase = ConnectionPhase.Connecting;
        Ui.StatusText = "Подключение…";
        Ui.SelectedDevice = selected;
        Ui.CredentialsReady = false;
        Ui.SetupPassword = "";
        Ui.SetupRepeatPassword = "";
        Ui.IdentifyLedLive = false;
        Ui.Error = null;
        RaiseUi();
    }

    public void PrepareScan()
    {
        ResetForDisconnect(clearCredentials: true);
        Ui = new DplsUiState
        {
            Phase = ConnectionPhase.Scanning,
            StatusText = "Поиск Test-DPLS…",
        };
        RaiseUi();
    }

    public void SetDevices(List<DiscoveredDevice> devices)
    {
        Ui.Devices = devices;
        Ui.StatusText = $"Найдено: {devices.Count}";
        RaiseUi();
    }

    public void StopScanUi()
    {
        Ui.Phase = ConnectionPhase.Idle;
        Ui.StatusText = Ui.Devices.Count == 0 ? "Устройства не найдены" : "Выберите устройство";
        RaiseUi();
    }

    public void HandleFrame(ReadOnlySpan<byte> bytes)
    {
        switch (DplsProtocol.Decode(bytes))
        {
            case DplsProtocol.DecodeResult.Failure f:
                Fail(f.Reason);
                break;
            case DplsProtocol.DecodeResult.Success s:
                HandleMessage(s.Frame);
                break;
        }
    }

    public string EventLogCsv()
    {
        var boot = Ui.DeviceBootEpochSeconds;
        var firstSeq = Ui.EventLog.Where(e => e.Type == 1).Select(e => e.Sequence).DefaultIfEmpty(0u).Max();
        var sb = new StringBuilder();
        sb.AppendLine("sequence;datetime;uptime_seconds;event_type;parameter;event");
        foreach (var e in Ui.EventLog)
        {
            var ts = DplsEventFormatting.Format(e, firstSeq, boot);
            sb.AppendLine($"{e.Sequence};{ts.Full};{e.TimestampSeconds};{e.Type};{e.Parameter};\"{DplsEventFormatting.Title(e.Type, e.Parameter)}\"");
        }
        return sb.ToString();
    }

    public string EventLogTxt()
    {
        var boot = Ui.DeviceBootEpochSeconds;
        var firstSeq = Ui.EventLog.Where(e => e.Type == 1).Select(e => e.Sequence).DefaultIfEmpty(0u).Max();
        var sb = new StringBuilder();
        sb.AppendLine("Журнал событий Тест-ДПЛС");
        sb.AppendLine($"Устройство: {Ui.DeviceInfo?.UserName ?? Ui.SelectedDevice?.UserName ?? "—"}");
        sb.AppendLine($"Записей: {Ui.EventLog.Count}");
        sb.AppendLine(new string('—', 32));
        foreach (var e in Ui.EventLog)
        {
            var ts = DplsEventFormatting.Format(e, firstSeq, boot);
            sb.AppendLine($"#{e.Sequence}  {ts.Full}  {DplsEventFormatting.Title(e.Type, e.Parameter)}");
        }
        return sb.ToString();
    }

    public bool HasCachedVerifier => _cachedVerifier != null;
    public bool ReachedReady => _reachedReady;
    public bool LogLoadPending => _logLoadPending;

    public void MarkReconnecting(string status)
    {
        Ui.Phase = ConnectionPhase.Reconnecting;
        Ui.StatusText = status;
        Ui.Authenticated = false;
        Ui.CredentialsReady = _cachedVerifier != null;
        RaiseUi();
    }

    private void HandleMessage(DplsProtocol.Frame frame)
    {
        var payload = frame.Payload.AsSpan();
        var offset = 0;
        switch (frame.Type)
        {
            case DplsProtocol.MessageType.AuthChallenge:
                if (payload.Length < 37)
                {
                    Fail("Повреждённый AUTH_CHALLENGE");
                    return;
                }
                _sessionId = LittleEndian.U32(payload, ref offset);
                _deviceNonce = payload.Slice(offset, 16).ToArray();
                offset += 16;
                _authSalt = payload.Slice(offset, 16).ToArray();
                offset += 16;
                _initialized = LittleEndian.U8(payload, ref offset) != 0;
                var autoAuth = _initialized && _cachedVerifier != null;
                Ui.Initialized = _initialized;
                Ui.CredentialsReady = true;
                Ui.AwaitingUserPassword = !autoAuth;
                Ui.StatusText = autoAuth ? "Вход…" : "Подключено";
                if (string.IsNullOrEmpty(Ui.SetupName))
                    Ui.SetupName = Ui.SelectedDevice?.UserName ?? "Test-DPLS-001";
                Ui.SetupPassword = "";
                Ui.SetupRepeatPassword = "";
                RaiseUi();
                SchedulePreAuthKeepAlive();
                if (autoAuth && _cachedVerifier is { } verifier)
                    SendAuthProof(verifier);
                break;

            case DplsProtocol.MessageType.AuthResult:
                if (Ui.Authenticated) return;
                CancelPreAuthKeepAlive();
                var status = LittleEndian.U8(payload, ref offset);
                var retryAfter = payload.Length >= 3 ? (int)LittleEndian.U16(payload, ref offset) : 0;
                if (status == 3)
                {
                    Ui.Phase = ConnectionPhase.Reconnecting;
                    Ui.StatusText = "Настройка сохранена. Повторное подключение…";
                    Ui.CredentialsReady = true;
                    Ui.Initialized = true;
                    Ui.AwaitingUserPassword = false;
                    Ui.SetupPassword = "";
                    Ui.SetupRepeatPassword = "";
                    Ui.Error = null;
                    RaiseUi();
                    return;
                }
                if (status != 0)
                {
                    Ui.AwaitingUserPassword = true;
                    Fail(retryAfter > 0
                        ? $"Аутентификация заблокирована на {retryAfter} с"
                        : "Неверный пароль");
                    return;
                }
                if (payload.Length - offset >= 8)
                    _sessionToken = payload.Slice(offset, 8).ToArray();
                Ui.Authenticated = true;
                Ui.AwaitingUserPassword = false;
                Ui.IdentifyActive = false;
                Ui.IdentifyLedLive = false;
                Ui.Phase = ConnectionPhase.Synchronizing;
                Ui.StatusText = "Чтение состояния…";
                Ui.Error = null;
                RaiseUi();
                Send(DplsProtocol.MessageType.StateGet, AuthenticatedPayload());
                ScheduleKeepAlive();
                break;

            case DplsProtocol.MessageType.CommandResult:
                if (payload.Length < 8)
                {
                    Fail("Повреждённый COMMAND_RESULT");
                    return;
                }
                _ = LittleEndian.U32(payload, ref offset);
                var result = LittleEndian.U8(payload, ref offset);
                _ = LittleEndian.U8(payload, ref offset);
                _ = LittleEndian.U16(payload, ref offset);
                if (result != 0)
                {
                    Fail(CommandRejectReason(result));
                    return;
                }
                Ui.CommandInProgress = false;
                Ui.StatusText = "Команда применена, чтение состояния…";
                Ui.LastAckMillis = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                RaiseUi();
                if (Ui.LogProgress == null)
                    Send(DplsProtocol.MessageType.StateGet, AuthenticatedPayload());
                break;

            case DplsProtocol.MessageType.DeviceInfoReport:
                ParseDeviceInfo(payload);
                break;

            case DplsProtocol.MessageType.SettingsResult:
                if (payload.Length < 5) return;
                var cmdId = LittleEndian.U32(payload, ref offset);
                var settingsStatus = LittleEndian.U8(payload, ref offset);
                if (_pendingSettings is not { } op || op.CommandId != cmdId) return;
                CancelSettingsTimeout();
                _pendingSettings = null;
                if (settingsStatus == 0)
                {
                    if (op.Kind == PendingSettingsKind.Password && op.NewVerifier != null)
                        _cachedVerifier = op.NewVerifier;
                    else if (op.Kind == PendingSettingsKind.Name && IsLinked)
                        RequestDeviceInfoInternal();
                    Ui.SettingsOp = SettingsOp.Done;
                    Ui.SettingsError = null;
                }
                else
                {
                    Ui.SettingsOp = SettingsOp.Failed;
                    Ui.SettingsError = $"Устройство отклонило изменение (код {settingsStatus})";
                }
                RaiseUi();
                break;

            case DplsProtocol.MessageType.StateReport:
                ParseState(payload);
                break;

            case DplsProtocol.MessageType.LogInfo:
                if (payload.Length < 10)
                {
                    FailLog("Повреждённый LOG_INFO");
                    return;
                }
                _ = LittleEndian.U32(payload, ref offset);
                var totalBytes = (int)LittleEndian.U32(payload, ref offset);
                var rawCount = (int)LittleEndian.U16(payload, ref offset);
                _logExpectedEvents = Math.Min(Math.Min(rawCount, totalBytes / 10), MaxLogEvents);
                _logExpectedEvents = Math.Max(0, _logExpectedEvents);
                _logExpectedBytes = _logExpectedEvents * 10;
                _logInfoReceived = true;
                _logReceivedEvents = 0;
                if (_logExpectedEvents == 0)
                {
                    _logBytes = [];
                    _logChunkReceived = [];
                    FinishLog();
                    return;
                }
                _logBytes = new byte[_logExpectedBytes];
                _logChunkReceived = new bool[_logExpectedEvents];
                foreach (var (chunk, data) in _logPendingChunks.OrderBy(x => x.Index))
                    ApplyLogChunk(chunk, data);
                _logPendingChunks.Clear();
                AfterChunkBatch();
                break;

            case DplsProtocol.MessageType.LogChunk:
                ParseLogChunk(payload);
                break;

            case DplsProtocol.MessageType.LogResult:
                FinishLog();
                break;

            case DplsProtocol.MessageType.Error:
                var code = payload.Length > 0 ? payload[0] : 0;
                if (Ui.LogProgress != null)
                {
                    FailLog($"Ошибка загрузки журнала: {code}");
                    return;
                }
                if (code == 5 && _awaitingDeviceInfo)
                {
                    _awaitingDeviceInfo = false;
                    _legacyFirmware = true;
                    return;
                }
                if (code == 5 && _pendingSettings != null)
                {
                    ClearPendingSettings();
                    _legacyFirmware = true;
                    Ui.SettingsOp = SettingsOp.Failed;
                    Ui.SettingsError = "Прошивка устройства не поддерживает изменение настроек";
                    RaiseUi();
                    return;
                }
                Fail(DeviceErrorReason(code));
                break;
        }
    }

    private void ParseDeviceInfo(ReadOnlySpan<byte> raw)
    {
        _awaitingDeviceInfo = false;
        if (raw.Length < 12) return;
        var o = 0;
        var deviceId = LittleEndian.U32(raw, ref o);
        var proto = LittleEndian.U8(raw, ref o);
        var major = LittleEndian.U8(raw, ref o);
        var minor = LittleEndian.U8(raw, ref o);
        var patch = LittleEndian.U8(raw, ref o);
        var hwRev = LittleEndian.U8(raw, ref o);
        var caps = LittleEndian.U8(raw, ref o);
        _ = LittleEndian.U8(raw, ref o);
        var nameLen = LittleEndian.U8(raw, ref o);
        var name = "";
        if (nameLen > 0 && 12 + nameLen <= raw.Length)
            name = Encoding.UTF8.GetString(raw.Slice(12, nameLen));

        Ui.DeviceInfo = new DeviceInfo
        {
            DeviceId = deviceId,
            ProtocolVersion = proto,
            FirmwareVersion = $"{major}.{minor}.{patch}",
            HardwareRevision = hwRev,
            AdcPresent = (caps & 0x01) != 0,
            HardwareReadback = (caps & 0x02) != 0,
            AdcCalibrated = (caps & 0x04) != 0,
            UserName = name,
        };
        if (Ui.SelectedDevice != null && !string.IsNullOrEmpty(name))
            Ui.SelectedDevice.UserName = name;
        RaiseUi();
    }

    private void ParseState(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 16)
        {
            Fail("Повреждённый STATE_REPORT");
            return;
        }
        var o = 0;
        var mode = DplsModeInfo.FromWire(LittleEndian.U8(payload, ref o)) ?? DplsMode.Normal;
        var power = LittleEndian.U8(payload, ref o) == 0 ? PowerSource.Dpls : PowerSource.Reserve;
        var voltage = LittleEndian.U16(payload, ref o);
        var automaticReturn = LittleEndian.U16(payload, ref o);
        var reserveLow = LittleEndian.U8(payload, ref o) != 0;
        var flags = LittleEndian.U8(payload, ref o);
        var realShort = (flags & 0x02) != 0;
        var uptimeSeconds = LittleEndian.U32(payload, ref o);
        var revision = LittleEndian.U32(payload, ref o);
        var validity = payload.Length > 16 ? LittleEndian.U8(payload, ref o) : (byte)0x00;
        var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var bootEpoch = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - uptimeSeconds;

        Ui.Phase = ConnectionPhase.Ready;
        Ui.StatusText = "Состояние получено";
        Ui.State = new DeviceState
        {
            Mode = mode,
            VoltageMv = voltage,
            PowerSource = power,
            ReserveLow = reserveLow,
            RealShort = realShort,
            AutomaticReturnSeconds = automaticReturn,
            UptimeSeconds = uptimeSeconds,
            Revision = revision,
            ReceivedAtMillis = nowMs,
            LineVoltageValid = (validity & 0x01) != 0,
            ReserveValid = (validity & 0x02) != 0,
            PowerValid = (validity & 0x04) != 0,
            AutoIsoValid = (validity & 0x08) != 0,
            AdcCalibrated = (validity & 0x10) != 0,
        };
        Ui.DeviceBootEpochSeconds = bootEpoch;
        Ui.Authenticated = true;
        Ui.IdentifyActive = false;
        Ui.IdentifyLedLive = false;
        Ui.CommandInProgress = false;
        Ui.StaleState = false;
        Ui.LastAckMillis = nowMs;
        Ui.Error = null;
        _reachedReady = true;
        RaiseUi();
        if (Ui.LogProgress == null) UpdateStateRefreshSchedule();
        if (Ui.DeviceInfo == null && !_legacyFirmware && !_awaitingDeviceInfo && Ui.LogProgress == null)
            RequestDeviceInfoInternal();
        if (_logLoadPending) LoadEventLog();
    }

    private void ParseLogChunk(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 3) return;
        var o = 0;
        var first = LittleEndian.U16(payload, ref o);
        var count = LittleEndian.U8(payload, ref o);
        if (count == 0 || payload.Length - o < count * 10) return;
        if (!_logInfoReceived)
        {
            for (var i = 0; i < count; i++)
            {
                var data = payload.Slice(o, 10).ToArray();
                o += 10;
                var idx = first + i;
                _logPendingChunks.RemoveAll(x => x.Index == idx);
                _logPendingChunks.Add((idx, data));
            }
            return;
        }
        for (var i = 0; i < count; i++)
        {
            var data = payload.Slice(o, 10).ToArray();
            o += 10;
            ApplyLogChunk(first + i, data);
        }
        AfterChunkBatch();
    }

    private void ApplyLogChunk(int chunk, byte[] data)
    {
        if (_logExpectedEvents <= 0 || chunk < 0 || chunk >= _logExpectedEvents) return;
        if (_logChunkReceived[chunk]) return;
        data.CopyTo(_logBytes.AsSpan(chunk * 10));
        _logChunkReceived[chunk] = true;
        _logReceivedEvents++;
    }

    private void AfterChunkBatch()
    {
        if (_logExpectedEvents <= 0) return;
        var progress = (float)_logReceivedEvents / _logExpectedEvents;
        Ui.LogProgress = Math.Clamp(progress, 0.05f, 1f);
        RaiseUi();
        if (_logReceivedEvents >= _logExpectedEvents) FinishLog();
        else ScheduleLogAck();
    }

    private void ScheduleLogAck()
    {
        var next = Array.FindIndex(_logChunkReceived, x => !x);
        if (next < 0) next = _logExpectedEvents;
        _pendingLogAckIndex = next;
        _logAckCts?.Cancel();
        var cts = new CancellationTokenSource();
        _logAckCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(20, cts.Token);
                Post(FlushLogAck);
            }
            catch (OperationCanceledException) { }
        });
    }

    private void FlushLogAck()
    {
        if (Ui.LogProgress == null) return;
        if (_transport.WriteInProgress)
        {
            ScheduleLogAck();
            return;
        }
        if (_pendingLogAckIndex is not int index) return;
        _pendingLogAckIndex = null;
        var chunk = new List<byte>();
        LittleEndian.AppendU16(chunk, (ushort)index);
        var bytes = DplsProtocol.Encode(new DplsProtocol.Frame(
            DplsProtocol.MessageType.LogAck,
            NextSequence(),
            LittleEndian.Concat(AuthenticatedPayload(), chunk.ToArray())));
        _transport.EnqueuePriority(bytes, flush: true);
    }

    private void FinishLog()
    {
        _logLoadTimeoutCts?.Cancel();
        _logAckCts?.Cancel();
        _logInfoReceived = false;
        var records = new List<EventRecord>();
        var o = 0;
        var span = _logBytes.AsSpan();
        while (o + 10 <= span.Length)
        {
            var seq = LittleEndian.U32(span, ref o);
            var ts = LittleEndian.U32(span, ref o);
            var type = LittleEndian.U8(span, ref o);
            var param = LittleEndian.U8(span, ref o);
            records.Add(new EventRecord(seq, ts, type, param));
        }
        records.Sort((a, b) => b.Sequence.CompareTo(a.Sequence));
        _logLoadPending = false;
        Ui.EventLog = records;
        Ui.LogProgress = null;
        Ui.StatusText = $"Журнал загружен: {records.Count} записей";
        Ui.Error = null;
        RaiseUi();
        ScheduleKeepAlive();
        UpdateStateRefreshSchedule();
    }

    private void SendAuthProof(byte[] verifier)
    {
        var signed = new List<byte>();
        signed.AddRange(_deviceNonce);
        signed.AddRange(_clientNonce);
        LittleEndian.AppendU32(signed, _sessionId);
        var mac = DplsCrypto.HmacSha256(verifier, signed.ToArray());
        Send(DplsProtocol.MessageType.AuthProof, LittleEndian.Concat(_clientNonce, mac));
    }

    private byte[] AuthenticatedPayload()
    {
        var data = new List<byte>();
        LittleEndian.AppendU32(data, _sessionId);
        data.AddRange(_sessionToken);
        return data.ToArray();
    }

    private void Send(DplsProtocol.MessageType type, byte[]? payload = null)
    {
        var bytes = DplsProtocol.Encode(new DplsProtocol.Frame(type, NextSequence(), payload ?? []));
        if (bytes.Length > NegotiatedWriteLimit)
        {
            Fail($"Кадр {bytes.Length} байт не помещается в лимит записи {NegotiatedWriteLimit}");
            return;
        }
        if (Ui.LogProgress != null) return;
        _transport.Enqueue(bytes);
    }

    private void SendPriority(DplsProtocol.MessageType type, byte[]? payload = null, bool flush = false)
    {
        var bytes = DplsProtocol.Encode(new DplsProtocol.Frame(type, NextSequence(), payload ?? []));
        if (bytes.Length > NegotiatedWriteLimit)
        {
            Fail($"Кадр {bytes.Length} байт не помещается в лимит записи {NegotiatedWriteLimit}");
            return;
        }
        _transport.EnqueuePriority(bytes, flush);
    }

    private ushort NextSequence()
    {
        var current = _sequence;
        _sequence++;
        return current;
    }

    private void RequestDeviceInfoInternal()
    {
        _awaitingDeviceInfo = true;
        Send(DplsProtocol.MessageType.DeviceInfoGet, AuthenticatedPayload());
    }

    private void ArmPendingSettings(PendingSettings op)
    {
        ClearPendingSettings();
        _pendingSettings = op;
        var cts = new CancellationTokenSource();
        _settingsTimeoutCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(10_000, cts.Token);
                Post(() =>
                {
                    if (_pendingSettings == null) return;
                    ClearPendingSettings();
                    Ui.SettingsOp = SettingsOp.Failed;
                    Ui.SettingsError = "Устройство не ответило на изменение настроек";
                    RaiseUi();
                });
            }
            catch (OperationCanceledException) { }
        });
    }

    private void ClearPendingSettings()
    {
        CancelSettingsTimeout();
        _pendingSettings = null;
    }

    private void CancelSettingsTimeout() => _settingsTimeoutCts?.Cancel();

    private void ResetLogTransfer()
    {
        _logBytes = [];
        _logExpectedBytes = 0;
        _logExpectedEvents = 0;
        _logReceivedEvents = 0;
        _logChunkReceived = [];
        _logInfoReceived = false;
        _pendingLogAckIndex = null;
        _logPendingChunks.Clear();
    }

    private void ResetWriteAwareState() => _transport.ResetQueue();

    private void SchedulePreAuthKeepAlive()
    {
        CancelPreAuthKeepAlive();
        var cts = new CancellationTokenSource();
        _preAuthKeepAliveCts = cts;
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(3000, cts.Token);
                    Post(() =>
                    {
                        if (Ui.IdentifyActive) return;
                        if (Ui.CredentialsReady && !Ui.Authenticated && IsLinked)
                            Send(DplsProtocol.MessageType.KeepAlive);
                    });
                }
                catch (OperationCanceledException) { break; }
            }
        });
    }

    private void CancelPreAuthKeepAlive() => _preAuthKeepAliveCts?.Cancel();

    private void ScheduleKeepAlive()
    {
        CancelKeepAlive();
        var cts = new CancellationTokenSource();
        _keepAliveCts = cts;
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(3000, cts.Token);
                    Post(() =>
                    {
                        if (Ui.Authenticated && IsLinked && Ui.LogProgress == null)
                            Send(DplsProtocol.MessageType.KeepAlive, AuthenticatedPayload());
                    });
                }
                catch (OperationCanceledException) { break; }
            }
        });
    }

    private void CancelKeepAlive() => _keepAliveCts?.Cancel();

    private void UpdateStateRefreshSchedule()
    {
        CancelStateRefresh();
        if (Ui.LogProgress != null) return;
        if (!Ui.Authenticated || Ui.CommandInProgress || Ui.State?.Mode == DplsMode.Normal || !IsLinked)
            return;
        var cts = new CancellationTokenSource();
        _stateRefreshCts = cts;
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(1000, cts.Token);
                    Post(() =>
                    {
                        if (Ui.LogProgress != null) return;
                        if (Ui.Authenticated && Ui.State?.Mode != DplsMode.Normal && IsLinked)
                            Send(DplsProtocol.MessageType.StateGet, AuthenticatedPayload());
                    });
                }
                catch (OperationCanceledException) { break; }
            }
        });
    }

    private void CancelStateRefresh() => _stateRefreshCts?.Cancel();

    private void ArmLogLoadTimeout()
    {
        _logLoadTimeoutCts?.Cancel();
        var cts = new CancellationTokenSource();
        _logLoadTimeoutCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(240_000, cts.Token);
                Post(() =>
                {
                    if (Ui.LogProgress == null) return;
                    _logInfoReceived = false;
                    _logLoadPending = false;
                    Ui.LogProgress = null;
                    Ui.Error = "Не удалось загрузить журнал";
                    RaiseUi();
                    ScheduleKeepAlive();
                    UpdateStateRefreshSchedule();
                });
            }
            catch (OperationCanceledException) { }
        });
    }

    private void ArmCommandTimeout()
    {
        _commandTimeoutCts?.Cancel();
        var cts = new CancellationTokenSource();
        _commandTimeoutCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(3000, cts.Token);
                Post(() =>
                {
                    if (!Ui.CommandInProgress) return;
                    Send(DplsProtocol.MessageType.StateGet, AuthenticatedPayload());
                    Ui.StatusText = "Запрос состояния устройства…";
                    RaiseUi();
                });
            }
            catch (OperationCanceledException) { }
        });
    }

    private void CancelTimers(bool keepReconnectRelevant)
    {
        CancelPreAuthKeepAlive();
        CancelKeepAlive();
        CancelStateRefresh();
        _logLoadTimeoutCts?.Cancel();
        _logAckCts?.Cancel();
        CancelSettingsTimeout();
        _commandTimeoutCts?.Cancel();
        _ = keepReconnectRelevant;
    }

    private void FailLog(string message)
    {
        _logLoadTimeoutCts?.Cancel();
        _logInfoReceived = false;
        _logLoadPending = false;
        Ui.LogProgress = null;
        Ui.Error = message;
        RaiseUi();
        ScheduleKeepAlive();
    }

    public void Fail(string message)
    {
        _logLoadTimeoutCts?.Cancel();
        _logInfoReceived = false;
        Ui.Phase = ConnectionPhase.Error;
        Ui.StatusText = message;
        Ui.Error = message;
        Ui.CommandInProgress = false;
        Ui.LogProgress = null;
        RaiseUi();
    }

    private static string DeviceErrorReason(int code) => code switch
    {
        7 => "Окно первичной настройки закрыто. Выключите и включите устройство, затем повторите настройку в течение нескольких минут.",
        _ => $"Ошибка устройства: {code}",
    };

    private static string CommandRejectReason(int status) => status switch
    {
        3 => "Команда отклонена: недопустимый режим",
        4 => "Команда отклонена: аппаратное переключение не удалось",
        5 => "Команда отклонена: активна автоизоляция реального КЗ",
        _ => $"Команда отклонена устройством: {status}",
    };

    private void RaiseUi() => Post(() => UiChanged?.Invoke());

    private void Post(Action action)
    {
        if (_sync != null)
            _sync.Post(_ => action(), null);
        else
            action();
    }
}

public interface IDplsTransport
{
    bool WriteInProgress { get; }
    void Enqueue(byte[] frame);
    void EnqueuePriority(byte[] frame, bool flush);
    void ResetQueue();
}
