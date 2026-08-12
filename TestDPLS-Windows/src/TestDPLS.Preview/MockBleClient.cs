using System.Buffers.Binary;
using Avalonia.Threading;
using TestDPLS.Crypto;
using TestDPLS.Models;
using TestDPLS.Protocol;
using TestDPLS.Session;

namespace TestDPLS.Preview;

/// <summary>
/// Linux/desktop preview host: fake nearby devices + in-process "firmware" replies.
/// </summary>
public sealed class MockBleClient : IDplsTransport
{
    private readonly DplsSession _session;
    private readonly Queue<byte[]> _writeQueue = new();
    private readonly object _gate = new();
    private readonly byte[] _deviceNonce = DplsCrypto.RandomBytes(16);
    private readonly byte[] _salt;
    private readonly byte[] _verifier;
    private readonly byte[] _sessionToken = DplsCrypto.RandomBytes(8);
    private uint _sessionId = 0x42;
    private bool _initialized = true;
    private bool _writeInProgress;
    private string? _selected;
    private bool _identifyMode;
    private DplsMode _mode = DplsMode.Normal;
    private uint _revision = 1;
    private readonly List<EventRecord> _events =
    [
        new(1, 10, 1, 0),
        new(2, 12, 2, 0),
        new(3, 15, 4, 0),
        new(4, 40, 7, 3),
        new(5, 45, 8, 1),
    ];

    public MockBleClient()
    {
        _salt = DplsCrypto.RandomBytes(16);
        _verifier = DplsCrypto.DeriveVerifier("password1", _salt);
        _session = new DplsSession(this, null);
        _session.UiChanged += () => Dispatcher.UIThread.Post(() => UiChanged?.Invoke());
        _session.NegotiatedWriteLimit = 180;
    }

    public DplsUiState Ui => _session.Ui;
    public event Action? UiChanged;
    public bool WriteInProgress
    {
        get { lock (_gate) return _writeInProgress; }
    }

    public void StartScan()
    {
        Disconnect();
        _session.PrepareScan();
        _ = Task.Run(async () =>
        {
            await Task.Delay(600);
            var devices = new List<DiscoveredDevice>
            {
                new()
                {
                    Address = "AA:BB:CC:DD:EE:01",
                    AdvertisedName = "Test-DPLS-A101",
                    DeviceId = 0xA101u,
                    Rssi = -47,
                },
                new()
                {
                    Address = "AA:BB:CC:DD:EE:02",
                    AdvertisedName = "Test-DPLS-B202",
                    DeviceId = 0xB202u,
                    Rssi = -61,
                },
            };
            Post(() => _session.SetDevices(devices));
            await Task.Delay(400);
            Post(() =>
            {
                if (Ui.Phase == ConnectionPhase.Scanning)
                    _session.StopScanUi();
            });
        });
    }

    public void Identify(string address)
    {
        _identifyMode = true;
        _session.BeginIdentifyFlow();
        Connect(address);
    }

    public void IdentifyRepair(string address) => Identify(address);

    public void StopIdentify()
    {
        _identifyMode = false;
        _session.StopIdentify();
    }

    public void ConfirmIdentifiedDevice()
    {
        _identifyMode = false;
        _session.ConfirmIdentifiedDevice();
    }

    public void Connect(string address)
    {
        _selected = address;
        var selected = Ui.Devices.FirstOrDefault(d => d.Address == address);
        _session.PrepareConnect(selected);
        _ = Task.Run(async () =>
        {
            await Task.Delay(350);
            Post(() =>
            {
                _session.NotifyLinked();
                Ui.Phase = ConnectionPhase.Pairing;
                Ui.StatusText = "Подтвердите сопряжение…";
                Raise();
            });
            await Task.Delay(700);
            Post(() => _session.OnGattReady(startIdentify: _identifyMode || Ui.IdentifyActive));
        });
    }

    public void Disconnect()
    {
        _selected = null;
        _identifyMode = false;
        ResetQueue();
        _session.ResetForDisconnect(clearCredentials: true);
    }

    public void UpdateSetupName(string v) => _session.UpdateSetupName(v);
    public void UpdateSetupPassword(string v) => _session.UpdateSetupPassword(v);
    public void UpdateSetupRepeatPassword(string v) => _session.UpdateSetupRepeatPassword(v);

    public void Authenticate(string password)
    {
        Console.WriteLine($"[preview] Authenticate len={password.Length}");
        _session.Authenticate(password);
    }

    public void Setup(string name, string password)
    {
        _initialized = false;
        _session.Setup(name, password);
    }

    public void RequestMode(DplsMode mode) => _session.RequestMode(mode);
    public void CancelMode() => _session.CancelMode();
    public void ConfirmMode() => _session.ConfirmMode();
    public void ReturnToNormal() => _session.ReturnToNormal();
    public void RequestDeviceInfo() => _session.RequestDeviceInfo();
    public void ClearSettingsOp() => _session.ClearSettingsOp();
    public void SetDeviceName(string name) => _session.SetDeviceName(name);
    public void ChangePassword(string current, string next) => _session.ChangePassword(current, next);
    public void LoadEventLog() => _session.LoadEventLog();
    public void RefreshState() => _session.RefreshState();
    public string EventLogCsv() => _session.EventLogCsv();
    public string EventLogTxt() => _session.EventLogTxt();

    public void Enqueue(byte[] frame) => EnqueueCore(frame, priority: false, flush: false);
    public void EnqueuePriority(byte[] frame, bool flush) => EnqueueCore(frame, priority: true, flush: flush);

    public void ResetQueue()
    {
        lock (_gate)
        {
            _writeQueue.Clear();
            _writeInProgress = false;
        }
    }

    private void EnqueueCore(byte[] frame, bool priority, bool flush)
    {
        lock (_gate)
        {
            if (flush) _writeQueue.Clear();
            if (priority)
            {
                var rest = _writeQueue.ToArray();
                _writeQueue.Clear();
                _writeQueue.Enqueue(frame);
                foreach (var f in rest) _writeQueue.Enqueue(f);
            }
            else _writeQueue.Enqueue(frame);
        }
        _ = DrainAsync();
    }

    private async Task DrainAsync()
    {
        byte[]? next;
        lock (_gate)
        {
            if (_writeInProgress || _writeQueue.Count == 0) return;
            next = _writeQueue.Dequeue();
            _writeInProgress = true;
        }

        await Task.Delay(40);
        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            lock (_gate) _writeInProgress = false;
            _session.OnWriteCompleted(true);
            HandleClientFrame(next!);
        });
        await DrainAsync();
    }

    private void HandleClientFrame(byte[] bytes)
    {
        if (DplsProtocol.Decode(bytes) is not DplsProtocol.DecodeResult.Success ok)
        {
            Console.WriteLine("[preview] bad frame from client");
            return;
        }

        var frame = ok.Frame;
        Console.WriteLine($"[preview] RX {frame.Type} payload={frame.Payload.Length}");
        switch (frame.Type)
        {
            case DplsProtocol.MessageType.Hello:
                ReplyAuthChallenge();
                break;
            case DplsProtocol.MessageType.AuthProof:
                ReplyAuthResult(frame.Payload);
                break;
            case DplsProtocol.MessageType.Setup:
                _initialized = true;
                if (frame.Payload.Length >= 5)
                {
                    var nameLen = frame.Payload[4];
                    if (5 + nameLen + 48 <= frame.Payload.Length)
                    {
                        Array.Copy(frame.Payload, 5 + nameLen, _salt, 0, 16);
                        Array.Copy(frame.Payload, 5 + nameLen + 16, _verifier, 0, 32);
                    }
                }
                Reply(DplsProtocol.MessageType.AuthResult, [3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
                _ = Task.Run(async () =>
                {
                    await Task.Delay(400);
                    Post(() =>
                    {
                        if (_selected != null) Connect(_selected);
                    });
                });
                break;
            case DplsProtocol.MessageType.StateGet:
                ReplyState();
                break;
            case DplsProtocol.MessageType.ModeSet:
                if (frame.Payload.Length >= 17)
                {
                    var cmdId = BinaryPrimitives.ReadUInt32LittleEndian(frame.Payload.AsSpan(12));
                    _mode = (DplsMode)frame.Payload[16];
                    _revision++;
                    var payload = new byte[8];
                    BinaryPrimitives.WriteUInt32LittleEndian(payload, cmdId);
                    payload[4] = 0;
                    payload[5] = (byte)_mode;
                    BinaryPrimitives.WriteUInt16LittleEndian(payload.AsSpan(6), (ushort)(_mode == DplsMode.Normal ? 0 : 300));
                    Reply(DplsProtocol.MessageType.CommandResult, payload);
                }
                break;
            case DplsProtocol.MessageType.DeviceInfoGet:
                ReplyDeviceInfo();
                break;
            case DplsProtocol.MessageType.NameSet:
                ReplySettings(frame.Payload);
                break;
            case DplsProtocol.MessageType.PasswordSet:
                if (frame.Payload.Length >= 64)
                {
                    Array.Copy(frame.Payload, 16, _salt, 0, 16);
                    Array.Copy(frame.Payload, 32, _verifier, 0, 32);
                }
                ReplySettings(frame.Payload);
                break;
            case DplsProtocol.MessageType.LogStart:
                ReplyLog();
                break;
            case DplsProtocol.MessageType.LogAck:
                Reply(DplsProtocol.MessageType.LogResult, [0]);
                break;
        }
    }

    private void ReplyAuthChallenge()
    {
        var payload = new List<byte>();
        AppendU32(payload, _sessionId);
        payload.AddRange(_deviceNonce);
        payload.AddRange(_salt);
        payload.Add((byte)(_initialized ? 1 : 0));
        Reply(DplsProtocol.MessageType.AuthChallenge, payload.ToArray());
    }

    private void ReplyAuthResult(byte[] proofPayload)
    {
        // Preview accepts any well-formed proof so UI demo is not blocked by salt race.
        byte status = proofPayload.Length >= 48 ? (byte)0 : (byte)1;
        Console.WriteLine($"[preview] AUTH_RESULT status={status}");
        var payload = new List<byte> { status, 0, 0 };
        payload.AddRange(status == 0 ? _sessionToken : new byte[8]);
        Reply(DplsProtocol.MessageType.AuthResult, payload.ToArray());
    }

    private void ReplyState()
    {
        var payload = new byte[17];
        payload[0] = (byte)_mode;
        payload[1] = 0;
        BinaryPrimitives.WriteUInt16LittleEndian(payload.AsSpan(2), 24100);
        BinaryPrimitives.WriteUInt16LittleEndian(payload.AsSpan(4), (ushort)(_mode == DplsMode.Normal ? 0 : 300));
        payload[6] = 0;
        payload[7] = 0x01;
        BinaryPrimitives.WriteUInt32LittleEndian(payload.AsSpan(8), 120);
        BinaryPrimitives.WriteUInt32LittleEndian(payload.AsSpan(12), _revision);
        payload[16] = 0x1F;
        Reply(DplsProtocol.MessageType.StateReport, payload);
    }

    private void ReplyDeviceInfo()
    {
        var name = System.Text.Encoding.UTF8.GetBytes("Демо-ДПЛС");
        var payload = new List<byte>();
        AppendU32(payload, 0xA101u);
        payload.Add(1);
        payload.Add(1); payload.Add(0); payload.Add(0);
        payload.Add(1);
        payload.Add(0x07);
        payload.Add(1);
        payload.Add((byte)name.Length);
        payload.AddRange(name);
        Reply(DplsProtocol.MessageType.DeviceInfoReport, payload.ToArray());
    }

    private void ReplySettings(byte[] request)
    {
        var cmdId = request.Length >= 16 ? BinaryPrimitives.ReadUInt32LittleEndian(request.AsSpan(12)) : 1u;
        var payload = new byte[5];
        BinaryPrimitives.WriteUInt32LittleEndian(payload, cmdId);
        payload[4] = 0;
        Reply(DplsProtocol.MessageType.SettingsResult, payload);
        ReplyDeviceInfo();
    }

    private void ReplyLog()
    {
        var total = _events.Count * 10;
        var info = new byte[10];
        BinaryPrimitives.WriteUInt32LittleEndian(info, _sessionId);
        BinaryPrimitives.WriteUInt32LittleEndian(info.AsSpan(4), (uint)total);
        BinaryPrimitives.WriteUInt16LittleEndian(info.AsSpan(8), (ushort)_events.Count);
        Reply(DplsProtocol.MessageType.LogInfo, info);

        var chunk = new List<byte>();
        AppendU16(chunk, 0);
        chunk.Add((byte)_events.Count);
        foreach (var e in _events.OrderBy(x => x.Sequence))
        {
            AppendU32(chunk, e.Sequence);
            AppendU32(chunk, e.TimestampSeconds);
            chunk.Add((byte)e.Type);
            chunk.Add((byte)e.Parameter);
        }
        Reply(DplsProtocol.MessageType.LogChunk, chunk.ToArray());
    }

    private void Reply(DplsProtocol.MessageType type, byte[] payload)
    {
        var frame = DplsProtocol.Encode(new DplsProtocol.Frame(type, 1, payload));
        Console.WriteLine($"[preview] TX {type} payload={payload.Length}");
        _session.HandleFrame(frame);
    }

    private static void AppendU16(List<byte> data, ushort v)
    {
        Span<byte> tmp = stackalloc byte[2];
        BinaryPrimitives.WriteUInt16LittleEndian(tmp, v);
        data.AddRange(tmp.ToArray());
    }

    private static void AppendU32(List<byte> data, uint v)
    {
        Span<byte> tmp = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(tmp, v);
        data.AddRange(tmp.ToArray());
    }

    private void Raise() => UiChanged?.Invoke();
    private void Post(Action action) => Dispatcher.UIThread.Post(action);
}
