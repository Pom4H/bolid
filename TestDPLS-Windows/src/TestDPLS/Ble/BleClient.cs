using System.Collections.Concurrent;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using TestDPLS.Models;
using TestDPLS.Session;

namespace TestDPLS.Ble;

/// <summary>
/// Windows BLE transport + connection lifecycle for Test-DPLS.
/// Pairing/bonding is required because RX writes are encrypted on the device.
/// </summary>
public sealed class BleClient : IDplsTransport, IDisposable
{
    private readonly DplsSession _session;
    private readonly SynchronizationContext _sync;
    private readonly Queue<byte[]> _writeQueue = new();
    private readonly object _writeLock = new();
    private readonly ConcurrentDictionary<ulong, DiscoveredDevice> _known = new();

    private BluetoothLEAdvertisementWatcher? _watcher;
    private BluetoothLEDevice? _device;
    private GattDeviceService? _service;
    private GattCharacteristic? _rx;
    private GattCharacteristic? _tx;
    private string? _selectedAddress;
    private int _reconnectAttempt;
    private CancellationTokenSource? _scanCts;
    private CancellationTokenSource? _reconnectCts;
    private bool _writeInProgress;
    private bool _disposed;

    public BleClient()
    {
        _sync = SynchronizationContext.Current ?? new SynchronizationContext();
        _session = new DplsSession(this, _sync);
        _session.UiChanged += () => UiChanged?.Invoke();
    }

    public DplsUiState Ui => _session.Ui;
    public event Action? UiChanged;
    public bool WriteInProgress
    {
        get { lock (_writeLock) return _writeInProgress; }
    }

    public void StartScan()
    {
        DisconnectGatt(clearSelection: true);
        _session.PrepareScan();
        _known.Clear();

        try
        {
            _watcher?.Stop();
            _watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Active,
            };
            _watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(DplsSession.ServiceUuid);
            _watcher.Received += OnAdvertisementReceived;
            _watcher.Stopped += (_, __) => { };
            _watcher.Start();
        }
        catch (Exception ex)
        {
            _session.Fail($"Bluetooth недоступен: {ex.Message}");
            return;
        }

        _scanCts?.Cancel();
        var cts = new CancellationTokenSource();
        _scanCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(20_000, cts.Token);
                Post(StopScan);
            }
            catch (OperationCanceledException) { }
        });
    }

    public void StopScan()
    {
        _scanCts?.Cancel();
        try { _watcher?.Stop(); } catch { /* ignore */ }
        _watcher = null;
        if (_session.Ui.Phase == ConnectionPhase.Scanning)
            _session.StopScanUi();
    }

    public void Identify(string address)
    {
        _session.BeginIdentifyFlow();
        Connect(address);
    }

    public void StopIdentify() => _session.StopIdentify();
    public void ConfirmIdentifiedDevice() => _session.ConfirmIdentifiedDevice();
    public void UpdateSetupName(string v) => _session.UpdateSetupName(v);
    public void UpdateSetupPassword(string v) => _session.UpdateSetupPassword(v);
    public void UpdateSetupRepeatPassword(string v) => _session.UpdateSetupRepeatPassword(v);
    public void Authenticate(string password) => _session.Authenticate(password);
    public void Setup(string name, string password) => _session.Setup(name, password);
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

    public void Disconnect()
    {
        _selectedAddress = null;
        DisconnectGatt(clearSelection: true);
        _session.ResetForDisconnect(clearCredentials: true);
    }

    public async void Connect(string address)
    {
        StopScan();
        CancelReconnect();
        _selectedAddress = address;
        CloseCurrentGatt();
        ResetQueue();
        _session.NotifyUnlinked(scheduleReconnectHint: false);
        _known.TryGetValue(ParseAddress(address), out var discovered);
        _session.PrepareConnect(discovered ?? Ui.Devices.FirstOrDefault(d => d.Address == address));
        await ConnectInternalAsync(address);
    }

    private async Task ConnectInternalAsync(string address)
    {
        try
        {
            var btAddress = ParseAddress(address);
            var device = await BluetoothLEDevice.FromBluetoothAddressAsync(btAddress);
            if (device == null)
            {
                _session.Fail("Устройство недоступно. Запустите поиск снова.");
                return;
            }

            _device = device;
            device.ConnectionStatusChanged += OnConnectionStatusChanged;

            // Trigger Windows pairing/bonding for encrypted GATT writes.
            try
            {
                var pairingResult = await device.DeviceInformation.Pairing.PairAsync();
                if (pairingResult.Status is not (DevicePairingResultStatus.Paired
                    or DevicePairingResultStatus.AlreadyPaired))
                {
                    // Continue — some stacks pair on first encrypted write.
                }
            }
            catch
            {
                // Pairing API may be unavailable; continue to GATT.
            }

            Ui.Phase = ConnectionPhase.Discovering;
            Ui.StatusText = "Подключение…";
            Raise();

            var services = await device.GetGattServicesForUuidAsync(DplsSession.ServiceUuid, BluetoothCacheMode.Uncached);
            if (services.Status != GattCommunicationStatus.Success || services.Services.Count == 0)
            {
                _session.Fail("Служба Test-DPLS не найдена");
                return;
            }

            _service = services.Services[0];
            var chars = await _service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached);
            if (chars.Status != GattCommunicationStatus.Success)
            {
                _session.Fail("Характеристики недоступны");
                return;
            }

            _rx = chars.Characteristics.FirstOrDefault(c => c.Uuid == DplsSession.RxUuid);
            _tx = chars.Characteristics.FirstOrDefault(c => c.Uuid == DplsSession.TxUuid);
            if (_rx == null || _tx == null)
            {
                _session.Fail("Служба Test-DPLS не найдена");
                return;
            }

            // Prefer a larger write limit when the stack reports one.
            _session.NegotiatedWriteLimit = Math.Max(20, (int)_rx.AttributeHandle > 0 ? 180 : 20);
            try
            {
                // Windows does not expose ATT MTU directly; 180 is safe for DPLS frames.
                _session.NegotiatedWriteLimit = 180;
            }
            catch { /* ignore */ }

            Ui.Phase = ConnectionPhase.Subscribing;
            Ui.StatusText = "Подключение…";
            Raise();

            _tx.ValueChanged += OnTxValueChanged;
            // CCCD 0x0003 = notify + indicate (same as mobile clients).
            var cccdValue = (GattClientCharacteristicConfigurationDescriptorValue)(
                (int)GattClientCharacteristicConfigurationDescriptorValue.Notify |
                (int)GattClientCharacteristicConfigurationDescriptorValue.Indicate);
            var cccd = await _tx.WriteClientCharacteristicConfigurationDescriptorAsync(cccdValue);
            if (cccd != GattCommunicationStatus.Success)
            {
                cccd = await _tx.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
                if (cccd != GattCommunicationStatus.Success)
                {
                    _session.Fail("Подписка на BLE-события не удалась");
                    return;
                }
            }

            _session.NotifyLinked();
            _session.OnGattReady(startIdentify: Ui.IdentifyActive);
            _reconnectAttempt = 0;
        }
        catch (Exception ex)
        {
            if (_selectedAddress != null)
                ScheduleReconnect();
            else
                _session.Fail($"Ошибка BLE: {ex.Message}");
        }
    }

    private void OnAdvertisementReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
            uint? deviceId = null;
        string? localName = args.Advertisement.LocalName;
        foreach (var mfg in args.Advertisement.ManufacturerData)
        {
            if (mfg.CompanyId != DplsSession.ManufacturerId || mfg.Data.Length < 4) continue;
            var raw = mfg.Data.ToArray();
            deviceId = (uint)(raw[0] | (raw[1] << 8) | (raw[2] << 16) | (raw[3] << 24));
            break;
        }

        var address = FormatAddress(args.BluetoothAddress);
        var name = !string.IsNullOrEmpty(localName)
            ? localName
            : deviceId is uint id
                ? $"Test-DPLS-{(id & 0xffff):X4}"
                : "Test-DPLS";

        var discovered = new DiscoveredDevice
        {
            Address = address,
            AdvertisedName = name,
            DeviceId = deviceId,
            Rssi = args.RawSignalStrengthInDBm,
        };
        _known[args.BluetoothAddress] = discovered;

        Post(() =>
        {
            var devices = Ui.Devices.Where(d => d.Address != address).ToList();
            devices.Add(discovered);
            devices.Sort((a, b) => b.Rssi.CompareTo(a.Rssi));
            _session.SetDevices(devices);
            if (_selectedAddress == address && Ui.Phase == ConnectionPhase.Reconnecting)
                Connect(address);
        });
    }

    private void OnTxValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var bytes = args.CharacteristicValue.ToArray();
        Post(() => _session.HandleFrame(bytes));
    }

    private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
        {
            Post(() =>
            {
                if (_selectedAddress == null) return;
                _session.NotifyUnlinked(scheduleReconnectHint: true);
                ScheduleReconnect();
            });
        }
    }

    public void Enqueue(byte[] frame)
    {
        lock (_writeLock)
        {
            _writeQueue.Enqueue(frame);
        }
        _ = DrainWriteQueueAsync();
    }

    public void EnqueuePriority(byte[] frame, bool flush)
    {
        lock (_writeLock)
        {
            if (flush) _writeQueue.Clear();
            var list = _writeQueue.ToList();
            _writeQueue.Clear();
            _writeQueue.Enqueue(frame);
            foreach (var item in list) _writeQueue.Enqueue(item);
        }
        _ = DrainWriteQueueAsync();
    }

    public void ResetQueue()
    {
        lock (_writeLock)
        {
            _writeQueue.Clear();
            _writeInProgress = false;
        }
    }

    private async Task DrainWriteQueueAsync()
    {
        byte[]? next;
        lock (_writeLock)
        {
            if (_writeInProgress || _rx == null || _writeQueue.Count == 0) return;
            next = _writeQueue.Dequeue();
            _writeInProgress = true;
        }

        try
        {
            var status = await _rx!.WriteValueAsync(next.AsBuffer(), GattWriteOption.WriteWithResponse);
            var ok = status == GattCommunicationStatus.Success;
            Post(() =>
            {
                lock (_writeLock) _writeInProgress = false;
                if (!ok)
                {
                    if (Ui.Phase is ConnectionPhase.Pairing or ConnectionPhase.Authenticating ||
                        (Ui.CredentialsReady && !Ui.Authenticated))
                    {
                        // Retry shortly while pairing dialog may be shown.
                        _ = Task.Delay(300).ContinueWith(_ => DrainWriteQueueAsync());
                        return;
                    }
                    if (_session.ReachedReady)
                    {
                        CloseCurrentGatt();
                        ScheduleReconnect();
                        return;
                    }
                    _session.Fail($"Ошибка передачи BLE: {status}");
                    return;
                }
                _session.OnWriteCompleted(true);
                _ = DrainWriteQueueAsync();
            });
        }
        catch (Exception ex)
        {
            Post(() =>
            {
                lock (_writeLock) _writeInProgress = false;
                if (Ui.Phase is ConnectionPhase.Pairing || Ui.IdentifyActive)
                {
                    _ = Task.Delay(300).ContinueWith(_ => DrainWriteQueueAsync());
                    return;
                }
                _session.Fail($"Ошибка передачи BLE: {ex.Message}");
            });
        }
    }

    private void ScheduleReconnect()
    {
        if (_reconnectCts != null) return;
        if (!_session.ReachedReady && _reconnectAttempt >= 3)
        {
            _session.Fail("Не удалось установить устойчивое BLE-соединение");
            return;
        }

        _session.MarkReconnecting(_session.ReachedReady || _session.LogLoadPending
            ? "Восстановление связи…"
            : "Подключение…");

        var delays = new[] { 500, 1000, 2000, 4000, 5000 };
        var delay = delays[Math.Min(_reconnectAttempt, delays.Length - 1)];
        _reconnectAttempt++;
        var address = _selectedAddress;
        var cts = new CancellationTokenSource();
        _reconnectCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(delay, cts.Token);
                Post(() =>
                {
                    _reconnectCts = null;
                    if (address != null && _selectedAddress == address)
                        Connect(address);
                });
            }
            catch (OperationCanceledException)
            {
                Post(() => _reconnectCts = null);
            }
        });
    }

    private void CancelReconnect()
    {
        _reconnectCts?.Cancel();
        _reconnectCts = null;
    }

    private void DisconnectGatt(bool clearSelection)
    {
        StopScan();
        CancelReconnect();
        CloseCurrentGatt();
        ResetQueue();
        _reconnectAttempt = 0;
        if (clearSelection) _selectedAddress = null;
        _session.NotifyUnlinked(false);
    }

    private void CloseCurrentGatt()
    {
        try
        {
            if (_tx != null) _tx.ValueChanged -= OnTxValueChanged;
        }
        catch { /* ignore */ }
        _tx = null;
        _rx = null;
        try { _service?.Dispose(); } catch { /* ignore */ }
        _service = null;
        if (_device != null)
        {
            try { _device.ConnectionStatusChanged -= OnConnectionStatusChanged; } catch { /* ignore */ }
            try { _device.Dispose(); } catch { /* ignore */ }
            _device = null;
        }
    }

    private void Raise() => UiChanged?.Invoke();

    private void Post(Action action)
    {
        if (SynchronizationContext.Current == _sync) action();
        else _sync.Post(_ => action(), null);
    }

    private static ulong ParseAddress(string address)
    {
        var hex = address.Replace(":", "").Replace("-", "");
        return Convert.ToUInt64(hex, 16);
    }

    private static string FormatAddress(ulong address) =>
        string.Join(":", Enumerable.Range(0, 6)
            .Select(i => ((address >> ((5 - i) * 8)) & 0xff).ToString("X2")));

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Disconnect();
    }
}
