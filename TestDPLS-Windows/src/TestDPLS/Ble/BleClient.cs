using System.Collections.Concurrent;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Foundation;
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
    private GattSession? _gattSession;
    private GattDeviceService? _service;
    private GattCharacteristic? _rx;
    private GattCharacteristic? _tx;
    private string? _selectedAddress;
    private int _reconnectAttempt;
    private CancellationTokenSource? _scanCts;
    private CancellationTokenSource? _reconnectCts;
    private bool _writeInProgress;
    private bool _disposed;
    private bool _connectInProgress;
    private bool _disconnectDuringConnect;
    private bool _forceUnpair;
    private bool _lastConnectLookedLikeStaleBond;
    private TypedEventHandler<DeviceInformationCustomPairing, DevicePairingRequestedEventArgs>? _pairingHandler;

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

    public void Identify(string address, bool repairBond = false)
    {
        CancelReconnect();
        _reconnectAttempt = 0;
        // «Повторить сопряжение» and auto-repair clear the Windows side of a
        // desynced bond (firmware may have erased LTK while Windows kept it).
        _forceUnpair = repairBond;
        _session.BeginIdentifyFlow();
        Connect(address);
    }

    /// <summary>Identify after explicitly clearing the Windows Bluetooth bond.</summary>
    public void IdentifyRepair(string address) => Identify(address, repairBond: true);

    public void StopIdentify() => _session.StopIdentify();
    public void ConfirmIdentifiedDevice() => _session.ConfirmIdentifiedDevice();
    public void UpdateSetupName(string v) => _session.UpdateSetupName(v);
    public void UpdateSetupPassword(string v) => _session.UpdateSetupPassword(v);
    public void UpdateSetupRepeatPassword(string v) => _session.UpdateSetupRepeatPassword(v);
    public void Authenticate(string password)
    {
        // After Identify the Windows link is often half-dead by the time the
        // operator finishes typing the password. Always rebuild
        // Connect→Pair→Hello→Challenge, then send AUTH_PROOF on the fresh challenge.
        if (_selectedAddress != null && !Ui.Authenticated)
        {
            _session.StashPasswordForReconnect(password);
            _ = ReconnectForLoginAsync(_selectedAddress);
            return;
        }
        _session.Authenticate(password);
    }

    private async Task ReconnectForLoginAsync(string address)
    {
        CancelReconnect();
        CloseCurrentGatt();
        ResetQueue();
        _session.NotifyUnlinked(scheduleReconnectHint: false);
        _session.PrepareAuthReconnect();
        await ConnectWithBondRepairAsync(address);
    }
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
        await ConnectWithBondRepairAsync(address);
    }

    /// <summary>
    /// One connect attempt, then at most one automatic Unpair+Pair if the failure
    /// matches a stale Windows bond (works only after deleting the bond by hand today).
    /// </summary>
    private async Task ConnectWithBondRepairAsync(string address)
    {
        _lastConnectLookedLikeStaleBond = false;
        var forceUnpair = _forceUnpair;
        _forceUnpair = false;

        var ok = await ConnectInternalAsync(address, forceUnpair);
        if (ok || _disposed || _selectedAddress != address) return;

        if (!_lastConnectLookedLikeStaleBond) return;

        // First attempt failed with a paired/stale-looking bond — clear Windows LTK and retry once.
        Ui.Error = null;
        Ui.Phase = ConnectionPhase.Pairing;
        Ui.StatusText = "Сброс устаревшего сопряжения Windows…";
        if (Ui.IdentifyActive) Ui.IdentifyLedLive = false;
        Raise();

        await UnpairByAddressAsync(address);
        await Task.Delay(600);
        if (_disposed || _selectedAddress != address) return;

        _lastConnectLookedLikeStaleBond = false;
        _ = await ConnectInternalAsync(address, forceUnpair: true);
    }

    /// <returns>True when GATT is ready and the protocol handshake was started.</returns>
    private async Task<bool> ConnectInternalAsync(string address, bool forceUnpair)
    {
        _connectInProgress = true;
        _disconnectDuringConnect = false;
        var hadOrCreatedBond = false;
        try
        {
            var btAddress = ParseAddress(address);

            // Prefer FromIdAsync via selector — more stable on Windows than address alone.
            BluetoothLEDevice? device = await OpenDeviceAsync(btAddress);
            if (device == null)
            {
                _session.Fail("Устройство недоступно. Запустите поиск снова.");
                return false;
            }

            _device = device;
            device.ConnectionStatusChanged += OnConnectionStatusChanged;

            var access = await device.RequestAccessAsync();
            if (access != DeviceAccessStatus.Allowed)
            {
                _session.Fail($"Нет доступа к Bluetooth-устройству ({access}). Проверьте параметры конфиденциальности Windows.");
                return false;
            }

            try
            {
                _gattSession = await GattSession.FromDeviceIdAsync(device.BluetoothDeviceId);
                _gattSession.MaintainConnection = true;
            }
            catch
            {
                // Older stacks may not support GattSession; continue.
            }

            hadOrCreatedBond = device.DeviceInformation.Pairing.IsPaired;

            if (forceUnpair && device.DeviceInformation.Pairing.IsPaired)
            {
                Ui.Phase = ConnectionPhase.Pairing;
                Ui.StatusText = "Удаление старого сопряжения…";
                Raise();
                await UnpairDeviceAsync(device);
                CloseCurrentGatt();
                await Task.Delay(400);
                device = await OpenDeviceAsync(btAddress);
                if (device == null)
                {
                    _lastConnectLookedLikeStaleBond = true;
                    _session.Fail(
                        "После сброса сопряжения устройство временно недоступно.\n" +
                        "Подождите 2–3 с и нажмите «Повторить сопряжение».");
                    return false;
                }
                _device = device;
                device.ConnectionStatusChanged += OnConnectionStatusChanged;
                try
                {
                    _gattSession = await GattSession.FromDeviceIdAsync(device.BluetoothDeviceId);
                    _gattSession.MaintainConnection = true;
                }
                catch { /* ignore */ }
                hadOrCreatedBond = false;
            }

            Ui.Phase = ConnectionPhase.Pairing;
            Ui.StatusText = "Сопряжение…";
            Raise();
            var paired = await EnsurePairedAsync(device);
            if (paired) hadOrCreatedBond = true;

            // Windows often needs a fresh device handle after pairing.
            if (paired)
            {
                try
                {
                    device.ConnectionStatusChanged -= OnConnectionStatusChanged;
                    var addr = device.BluetoothAddress;
                    device.Dispose();
                    await Task.Delay(200);
                    device = await BluetoothLEDevice.FromBluetoothAddressAsync(addr);
                    if (device == null)
                    {
                        _lastConnectLookedLikeStaleBond = true;
                        _session.Fail("Устройство пропало после сопряжения. Повторите попытку.");
                        return false;
                    }
                    _device = device;
                    device.ConnectionStatusChanged += OnConnectionStatusChanged;
                    try
                    {
                        _gattSession?.Dispose();
                        _gattSession = await GattSession.FromDeviceIdAsync(device.BluetoothDeviceId);
                        _gattSession.MaintainConnection = true;
                    }
                    catch { /* ignore */ }
                }
                catch
                {
                    // Continue with existing handle.
                }
            }

            Ui.Phase = ConnectionPhase.Discovering;
            Ui.StatusText = "Поиск службы Test-DPLS…";
            Raise();

            var service = await FindDplsServiceAsync(device);
            if (service == null)
            {
                MarkStaleBondIf(hadOrCreatedBond);
                _session.Fail(
                    "Служба Test-DPLS не найдена.\n" +
                    "Часто помогает сброс сопряжения Windows — нажмите «Повторить сопряжение».");
                return false;
            }

            _service = service;
            var chars = await ResolveCharacteristicsAsync(service);
            if (chars == null)
            {
                MarkStaleBondIf(hadOrCreatedBond);
                _session.Fail(
                    "Характеристики RX/TX недоступны.\n" +
                    "Нажмите «Повторить сопряжение» (сброс bond Windows).");
                return false;
            }

            _rx = chars.Value.Rx;
            _tx = chars.Value.Tx;
            _session.NegotiatedWriteLimit = 180;

            Ui.Phase = ConnectionPhase.Subscribing;
            Ui.StatusText = "Подписка на уведомления…";
            Raise();

            _tx.ValueChanged += OnTxValueChanged;
            if (!await EnableTxNotificationsAsync(_tx))
            {
                MarkStaleBondIf(hadOrCreatedBond);
                _session.Fail(
                    "Подписка на BLE-события не удалась.\n" +
                    "Нажмите «Повторить сопряжение» (сброс bond Windows).");
                return false;
            }

            if (_disconnectDuringConnect ||
                device.ConnectionStatus != BluetoothConnectionStatus.Connected)
            {
                MarkStaleBondIf(hadOrCreatedBond);
                FailIdentifyOrConnect("Связь оборвалась до запуска протокола.");
                return false;
            }

            _session.NotifyLinked();
            _session.OnGattReady(startIdentify: Ui.IdentifyActive);
            _reconnectAttempt = 0;
            return true;
        }
        catch (Exception ex)
        {
            // During identify / first connect, wait for user retry instead of burning reconnects.
            if (_session.ReachedReady && _selectedAddress != null)
            {
                ScheduleReconnect();
                return false;
            }
            MarkStaleBondIf(hadOrCreatedBond);
            _session.Fail($"Ошибка BLE: {ex.Message}");
            return false;
        }
        finally
        {
            _connectInProgress = false;
            // Disconnect during connect was suppressed; surface it now if still down.
            if (_disconnectDuringConnect &&
                _selectedAddress != null &&
                !_session.ReachedReady &&
                (_device == null || _device.ConnectionStatus != BluetoothConnectionStatus.Connected))
            {
                MarkStaleBondIf(hadOrCreatedBond);
                FailIdentifyOrConnect("Связь оборвалась во время подключения.");
            }
        }
    }

    private void MarkStaleBondIf(bool hadBond)
    {
        if (hadBond) _lastConnectLookedLikeStaleBond = true;
    }

    private static async Task<BluetoothLEDevice?> OpenDeviceAsync(ulong btAddress)
    {
        try
        {
            var selector = BluetoothLEDevice.GetDeviceSelectorFromBluetoothAddress(btAddress);
            var infos = await DeviceInformation.FindAllAsync(selector);
            if (infos.Count > 0)
            {
                var fromId = await BluetoothLEDevice.FromIdAsync(infos[0].Id);
                if (fromId != null) return fromId;
            }
        }
        catch
        {
            // Fall through to address-based open.
        }

        try
        {
            return await BluetoothLEDevice.FromBluetoothAddressAsync(btAddress);
        }
        catch
        {
            return null;
        }
    }

    private async Task UnpairByAddressAsync(string address)
    {
        try
        {
            var device = _device ?? await OpenDeviceAsync(ParseAddress(address));
            if (device != null)
                await UnpairDeviceAsync(device);
        }
        catch
        {
            // Best-effort — user can still delete the bond in Windows Settings.
        }
        finally
        {
            CloseCurrentGatt();
        }
    }

    private static async Task UnpairDeviceAsync(BluetoothLEDevice device)
    {
        try
        {
            if (!device.DeviceInformation.Pairing.IsPaired) return;
            var result = await device.DeviceInformation.Pairing.UnpairAsync();
            _ = result;
        }
        catch
        {
            // Ignore — next PairAsync may still work after a partial unpair.
        }
    }

    private void FailIdentifyOrConnect(string detail)
    {
        if (Ui.IdentifyActive)
            _session.OnIdentifyLinkLost(detail);
        else
            _session.Fail(detail + "\nПовторите подключение.");
    }

    /// <summary>
    /// Windows rejects combined Notify|Indicate enum values ("parameter is incorrect").
    /// Enable Indicate (required for journal) or Notify via the WinRT API first.
    /// Do not write raw CCCD 0x0003 after a successful Indicate — that churn can drop
    /// the link on some Windows stacks and leave Identify claiming a live LED falsely.
    /// </summary>
    private static async Task<bool> EnableTxNotificationsAsync(GattCharacteristic tx)
    {
        var props = tx.CharacteristicProperties;
        var tried = new List<GattClientCharacteristicConfigurationDescriptorValue>();

        // Journal LOG_CHUNK uses indications — try Indicate before Notify.
        if (props.HasFlag(GattCharacteristicProperties.Indicate))
            tried.Add(GattClientCharacteristicConfigurationDescriptorValue.Indicate);
        if (props.HasFlag(GattCharacteristicProperties.Notify))
            tried.Add(GattClientCharacteristicConfigurationDescriptorValue.Notify);

        // If properties were not reported, try both singly (never OR-combined).
        if (tried.Count == 0)
        {
            tried.Add(GattClientCharacteristicConfigurationDescriptorValue.Indicate);
            tried.Add(GattClientCharacteristicConfigurationDescriptorValue.Notify);
        }

        foreach (var value in tried)
        {
            try
            {
                var status = await tx.WriteClientCharacteristicConfigurationDescriptorAsync(value);
                if (status == GattCommunicationStatus.Success)
                    return true;
            }
            catch (ArgumentException)
            {
                // Invalid enum for this stack — try next / raw descriptor.
            }
            catch (Exception)
            {
                // Continue to fallbacks.
            }
        }

        // Raw CCCD write only when WinRT CCCD API failed: 0x0002=indicate, 0x0001=notify.
        foreach (var payload in new byte[][]
                 {
                     [0x02, 0x00],
                     [0x01, 0x00],
                 })
        {
            if (await TryWriteRawCccdAsync(tx, payload))
                return true;
        }

        return false;
    }

    private static async Task<bool> TryWriteRawCccdAsync(GattCharacteristic tx, byte[] payload)
    {
        try
        {
            var descriptors = await tx.GetDescriptorsForUuidAsync(
                GattDescriptorUuids.ClientCharacteristicConfiguration,
                BluetoothCacheMode.Uncached);
            if (descriptors.Status != GattCommunicationStatus.Success || descriptors.Descriptors.Count == 0)
                return false;
            var status = await descriptors.Descriptors[0].WriteValueAsync(payload.AsBuffer());
            return status == GattCommunicationStatus.Success;
        }
        catch
        {
            return false;
        }
    }

    /// <returns>True when a new pairing was completed.</returns>
    private async Task<bool> EnsurePairedAsync(BluetoothLEDevice device)
    {
        try
        {
            if (device.DeviceInformation.Pairing.IsPaired) return false;

            var custom = device.DeviceInformation.Pairing.Custom;
            _pairingHandler = (_, args) => args.Accept();
            custom.PairingRequested += _pairingHandler;
            try
            {
                var result = await custom.PairAsync(
                    DevicePairingKinds.ConfirmOnly,
                    DevicePairingProtectionLevel.Encryption);
                return result.Status is DevicePairingResultStatus.Paired
                    or DevicePairingResultStatus.AlreadyPaired;
            }
            finally
            {
                if (_pairingHandler != null)
                    custom.PairingRequested -= _pairingHandler;
                _pairingHandler = null;
            }
        }
        catch
        {
            return false;
        }
    }

    private static async Task<GattDeviceService?> FindDplsServiceAsync(BluetoothLEDevice device)
    {
        // Prefer UUID lookup first (fast path once bonded); fall back to full scan.
        for (var attempt = 0; attempt < 4; attempt++)
        {
            var mode = attempt == 0 ? BluetoothCacheMode.Cached : BluetoothCacheMode.Uncached;

            var byUuid = await device.GetGattServicesForUuidAsync(DplsSession.ServiceUuid, mode);
            if (byUuid.Status == GattCommunicationStatus.Success && byUuid.Services.Count > 0)
                return byUuid.Services[0];

            var all = await device.GetGattServicesAsync(mode);
            if (all.Status == GattCommunicationStatus.Success)
            {
                var found = all.Services.FirstOrDefault(s => s.Uuid == DplsSession.ServiceUuid);
                if (found != null) return found;
            }

            if (attempt < 3)
                await Task.Delay(200 * (attempt + 1));
        }

        return null;
    }

    private static async Task<(GattCharacteristic Rx, GattCharacteristic Tx)?> ResolveCharacteristicsAsync(
        GattDeviceService service)
    {
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var mode = attempt == 0 ? BluetoothCacheMode.Uncached : BluetoothCacheMode.Cached;
            var chars = await service.GetCharacteristicsAsync(mode);
            if (chars.Status == GattCommunicationStatus.Success)
            {
                var rx = chars.Characteristics.FirstOrDefault(c => c.Uuid == DplsSession.RxUuid);
                var tx = chars.Characteristics.FirstOrDefault(c => c.Uuid == DplsSession.TxUuid);
                if (rx != null && tx != null) return (rx, tx);
            }

            var rxResult = await service.GetCharacteristicsForUuidAsync(DplsSession.RxUuid, mode);
            var txResult = await service.GetCharacteristicsForUuidAsync(DplsSession.TxUuid, mode);
            if (rxResult.Status == GattCommunicationStatus.Success &&
                txResult.Status == GattCommunicationStatus.Success &&
                rxResult.Characteristics.Count > 0 &&
                txResult.Characteristics.Count > 0)
            {
                return (rxResult.Characteristics[0], txResult.Characteristics[0]);
            }

            await Task.Delay(300 * (attempt + 1));
        }

        return null;
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
                // Ignore disconnect flaps while the initial connect/pair/discover is running,
                // but remember them — finally-block checks the link before claiming success.
                if (_connectInProgress)
                {
                    _disconnectDuringConnect = true;
                    return;
                }

                // Identify runs before READY: never keep "LED blinking" after the link drops.
                if (Ui.IdentifyActive && !_session.ReachedReady)
                {
                    FailIdentifyOrConnect("Связь оборвалась до запуска индикации.");
                    return;
                }

                // Login / Hello / AuthProof also run before READY. Ignoring disconnect here
                // left IsLinked=true and caused "Устройство не ответило на вход".
                if (!_session.ReachedReady)
                {
                    if (_session.IsLinked || Ui.CredentialsReady || Ui.AwaitingUserPassword ||
                        Ui.Phase is ConnectionPhase.Authenticating or ConnectionPhase.Pairing
                            or ConnectionPhase.Synchronizing or ConnectionPhase.Discovering
                            or ConnectionPhase.Subscribing)
                    {
                        _session.OnPreAuthLinkLost(
                            "Связь оборвалась до завершения входа.\n" +
                            "Введите пароль снова — приложение переподключится.");
                    }
                    return;
                }

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
                if (!ok)
                {
                    lock (_writeLock) _writeInProgress = false;
                    if (Ui.LogProgress != null)
                    {
                        // Journal transfer: retry LOG_ACK instead of tearing the link.
                        _session.OnLogWriteFailed();
                        return;
                    }
                    if (Ui.Phase is ConnectionPhase.Pairing or ConnectionPhase.Authenticating ||
                        Ui.IdentifyActive ||
                        (Ui.CredentialsReady && !Ui.Authenticated))
                    {
                        // Re-queue the same frame — previously we drained it and retried an
                        // empty queue, so AuthProof/Hello/Identify were lost and the UI hung.
                        RequeueFront(next);
                        _ = Task.Delay(350).ContinueWith(_ => DrainWriteQueueAsync());
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
                lock (_writeLock) _writeInProgress = false;
                // Only treat Identify as live when the ATT write succeeded AND the link is up.
                // Windows can report write Success while the peripheral already dropped.
                var stillUp = _device?.ConnectionStatus == BluetoothConnectionStatus.Connected;
                _session.OnWriteCompleted(stillUp);
                if (!stillUp && Ui.IdentifyActive && !_session.ReachedReady)
                {
                    FailIdentifyOrConnect("Связь оборвалась до запуска индикации.");
                    return;
                }
                _ = DrainWriteQueueAsync();
            });
        }
        catch (Exception ex)
        {
            Post(() =>
            {
                lock (_writeLock) _writeInProgress = false;
                if (Ui.LogProgress != null)
                {
                    _session.OnLogWriteFailed();
                    return;
                }
                if (Ui.Phase is ConnectionPhase.Pairing or ConnectionPhase.Authenticating ||
                    Ui.IdentifyActive ||
                    (Ui.CredentialsReady && !Ui.Authenticated))
                {
                    RequeueFront(next);
                    _ = Task.Delay(350).ContinueWith(_ => DrainWriteQueueAsync());
                    return;
                }
                _session.Fail($"Ошибка передачи BLE: {ex.Message}");
            });
        }
    }

    private void RequeueFront(byte[] frame)
    {
        lock (_writeLock)
        {
            var rest = _writeQueue.ToList();
            _writeQueue.Clear();
            _writeQueue.Enqueue(frame);
            foreach (var item in rest)
                _writeQueue.Enqueue(item);
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
        try
        {
            if (_gattSession != null)
            {
                _gattSession.MaintainConnection = false;
                _gattSession.Dispose();
            }
        }
        catch { /* ignore */ }
        _gattSession = null;
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
