using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using TestDPLS.Ble;
using TestDPLS.Models;

namespace TestDPLS;

public partial class MainWindow : Window
{
    private enum Page { Main, Log, Export, Settings, Name, Password, About }

    private readonly BleClient _client = new();
    private readonly DispatcherTimer _uiTimer;
    private Page _page = Page.Main;
    private DplsMode _chosenMode = DplsMode.Short1;
    private DiscoveredDevice? _identifying;
    private bool _pickingTest;

    public MainWindow()
    {
        InitializeComponent();
        _client.UiChanged += () =>
        {
            // BeginInvoke avoids nested Invoke deadlocks when BLE callbacks
            // already run on the dispatcher via SynchronizationContext.
            if (Dispatcher.CheckAccess())
                Render();
            else
                Dispatcher.BeginInvoke(Render);
        };
        _uiTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _uiTimer.Tick += (_, __) =>
        {
            if (_client.Ui.State?.Mode is { } m && DplsModeInfo.Dangerous(m))
                Render();
        };
        _uiTimer.Start();
        Loaded += (_, __) =>
        {
            _client.StartScan();
            Render();
        };
        Closed += (_, __) => _client.Dispose();
    }

    private DplsUiState S => _client.Ui;

    private void Render()
    {
        var connected = S.SelectedDevice != null;
        var showConnecting = !S.Authenticated && (!S.CredentialsReady || !S.AwaitingUserPassword);
        var showLogin = !S.Authenticated && S.CredentialsReady && S.AwaitingUserPassword;
        var showTabs = S.Authenticated && _page is Page.Main or Page.Log or Page.Settings;

        BottomNav.Visibility = showTabs ? Visibility.Visible : Visibility.Collapsed;
        UpdateNavColors();

        if (S.PendingMode is { } pending)
        {
            ConfirmOverlay.Visibility = Visibility.Visible;
            ConfirmText.Text = pending == DplsMode.Normal
                ? "Вернуть устройство в режим «Норма»?"
                : $"Включить режим «{DplsModeInfo.Title(pending)}»?";
            ConfirmHint.Text = pending == DplsMode.Normal
                ? "Испытание будет завершено."
                : $"{DplsModeInfo.PortHint(pending)}\n{DplsModeInfo.ControllerEffect(pending)}\nАвтовозврат через {S.State?.AutomaticReturnSeconds ?? 300} с.";
        }
        else
        {
            ConfirmOverlay.Visibility = Visibility.Collapsed;
        }

        if (_identifying != null && !S.Authenticated)
        {
            PageHost.Content = BuildIdentify(_identifying);
            return;
        }

        if (!connected)
        {
            PageHost.Content = BuildDevices();
            return;
        }

        if (showConnecting)
        {
            PageHost.Content = BuildConnecting();
            return;
        }

        if (showLogin)
        {
            PageHost.Content = BuildLogin();
            return;
        }

        if (_pickingTest)
        {
            PageHost.Content = BuildTestPicker();
            return;
        }

        PageHost.Content = _page switch
        {
            Page.Main => BuildOperation(),
            Page.Log => BuildLog(),
            Page.Export => BuildExport(),
            Page.Settings => BuildSettings(),
            Page.Name => BuildName(),
            Page.Password => BuildPassword(),
            Page.About => BuildAbout(),
            _ => BuildOperation(),
        };
    }

    private void UpdateNavColors()
    {
        var blue = (Brush)FindResource("BlueBrush");
        var muted = (Brush)FindResource("MutedBrush");
        NavMain.Foreground = _page == Page.Main ? blue : muted;
        NavLog.Foreground = _page == Page.Log ? blue : muted;
        NavSettings.Foreground = _page == Page.Settings ? blue : muted;
    }

    private UIElement TitleBar(string title, Action? back = null)
    {
        var grid = new Grid { Margin = new Thickness(18, 8, 18, 8), MinHeight = 64 };
        if (back != null)
        {
            var backBtn = new Button
            {
                Content = "‹",
                FontSize = 40,
                Foreground = Brushes.White,
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                HorizontalAlignment = HorizontalAlignment.Left,
                Cursor = System.Windows.Input.Cursors.Hand,
            };
            backBtn.Click += (_, __) => back();
            grid.Children.Add(backBtn);
        }
        grid.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 17,
            FontWeight = FontWeights.Medium,
            Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(42, 0, 42, 0),
        });
        return grid;
    }

    private Button Primary(string title, Action action, bool enabled = true, Brush? color = null)
    {
        var btn = new Button
        {
            Content = title,
            Style = (Style)FindResource("PrimaryButton"),
            IsEnabled = enabled,
            Background = color ?? (Brush)FindResource("BlueBrush"),
        };
        btn.Click += (_, __) => action();
        return btn;
    }

    private Button Secondary(string title, Action action)
    {
        var btn = new Button { Content = title, Style = (Style)FindResource("SecondaryButton") };
        btn.Click += (_, __) => action();
        return btn;
    }

    private Border Card(UIElement content)
    {
        return new Border
        {
            Background = (Brush)FindResource("PanelBrush"),
            BorderBrush = (Brush)FindResource("LineBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12),
            Margin = new Thickness(18, 8, 18, 8),
            Child = content,
        };
    }

    private UIElement BuildDevices()
    {
        var root = new DockPanel();
        DockPanel.SetDock(TitleBar("Устройства рядом"), Dock.Top);
        root.Children.Add(TitleBar("Устройства рядом"));

        var refresh = Primary(
            S.Phase == ConnectionPhase.Scanning ? "Обновление..." : "↻  Обновить",
            () => _client.StartScan(),
            S.Phase != ConnectionPhase.Scanning);
        DockPanel.SetDock(refresh, Dock.Bottom);
        root.Children.Add(refresh);

        var body = new StackPanel { Margin = new Thickness(20, 0, 20, 0) };
        body.Children.Add(new TextBlock
        {
            Text = S.Phase == ConnectionPhase.Scanning ? "Ищем устройства по BLE..." : "Доступные устройства",
            Foreground = (Brush)FindResource("MutedBrush"),
            FontSize = 12,
            Margin = new Thickness(0, 0, 0, 12),
        });

        if (S.Devices.Count == 0)
        {
            body.Children.Add(new TextBlock
            {
                Text = S.Phase == ConnectionPhase.Scanning ? "Идёт поиск устройств…" : "Устройства не найдены",
                Foreground = (Brush)FindResource("MutedBrush"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 40, 0, 8),
            });
            if (S.Phase != ConnectionPhase.Scanning)
            {
                body.Children.Add(new TextBlock
                {
                    Text = "Включите Bluetooth и поднесите ноутбук\nближе к устройству, затем обновите",
                    Foreground = (Brush)FindResource("MutedBrush"),
                    FontSize = 12,
                    TextAlignment = TextAlignment.Center,
                    HorizontalAlignment = HorizontalAlignment.Center,
                });
            }
        }
        else
        {
            var list = new StackPanel();
            foreach (var device in S.Devices)
            {
                var row = new Button
                {
                    Background = Brushes.Transparent,
                    BorderThickness = new Thickness(0, 0, 0, 1),
                    BorderBrush = (Brush)FindResource("LineBrush"),
                    HorizontalContentAlignment = HorizontalAlignment.Stretch,
                    Padding = new Thickness(0, 12, 0, 12),
                    Cursor = System.Windows.Input.Cursors.Hand,
                    Tag = device,
                };
                var grid = new Grid();
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var left = new StackPanel();
                left.Children.Add(new TextBlock
                {
                    Text = device.UserName ?? device.AdvertisedName,
                    Foreground = Brushes.White,
                    TextWrapping = TextWrapping.Wrap,
                });
                left.Children.Add(new TextBlock
                {
                    Text = device.Address,
                    Foreground = (Brush)FindResource("MutedBrush"),
                    FontSize = 11,
                });
                Grid.SetColumn(left, 0);
                var rssi = new TextBlock
                {
                    Text = $"{device.Rssi} дБм",
                    Foreground = (Brush)FindResource("GreenBrush"),
                    FontSize = 12,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0),
                };
                Grid.SetColumn(rssi, 1);
                grid.Children.Add(left);
                grid.Children.Add(rssi);
                row.Content = grid;
                row.Click += (_, __) =>
                {
                    _identifying = device;
                    Render();
                };
                list.Children.Add(row);
            }
            body.Children.Add(new ScrollViewer { Content = list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        }

        root.Children.Add(body);
        return root;
    }

    private UIElement BuildIdentify(DiscoveredDevice device)
    {
        var ledReady = S.IdentifyActive && S.IdentifyLedLive && S.Error == null;
        var root = new DockPanel();
        var title = TitleBar("Показать на объекте", () =>
        {
            _client.StopIdentify();
            _client.Disconnect();
            _identifying = null;
            Render();
        });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);

        var buttons = new StackPanel();
        var retry = S.Error != null;
        buttons.Children.Add(Primary(
            retry ? "Повторить сопряжение"
                : S.Phase == ConnectionPhase.Pairing ? "Ожидаем подтверждения…"
                : "Это устройство",
            () =>
            {
                if (retry) _client.Identify(device.Address);
                else
                {
                    _client.ConfirmIdentifiedDevice();
                    _identifying = null;
                }
                Render();
            },
            retry || ledReady));
        buttons.Children.Add(Secondary("Остановить", () =>
        {
            _client.StopIdentify();
            _client.Disconnect();
            _identifying = null;
            Render();
        }));
        DockPanel.SetDock(buttons, Dock.Bottom);
        root.Children.Add(buttons);

        var status = S.Error
            ?? (S.Phase == ConnectionPhase.Pairing
                ? "Подтвердите сопряжение\nв системном диалоге Bluetooth"
                : ledReady
                    ? "Светодиод на устройстве\nмигает с частотой 1 Гц"
                    : "Подключение к устройству…");

        var center = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(24),
        };
        center.Children.Add(new TextBlock
        {
            Text = status,
            Foreground = S.Error != null ? (Brush)FindResource("OrangeBrush") : Brushes.White,
            FontSize = 16,
            TextAlignment = TextAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(8, 0, 8, 0),
            MaxWidth = 360,
        });
        if (S.Phase == ConnectionPhase.Pairing)
        {
            center.Children.Add(new TextBlock
            {
                Text = "Окно открылось поверх приложения",
                Foreground = (Brush)FindResource("MutedBrush"),
                FontSize = 13,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 12, 0, 0),
            });
        }
        else if (ledReady == false && S.IdentifyActive && S.Error == null)
        {
            center.Children.Add(new ProgressBar { IsIndeterminate = true, Height = 4, Margin = new Thickness(40, 18, 40, 0) });
        }

        root.Children.Add(center);

        // Kick identify on first show.
        if (!S.IdentifyActive && S.Phase is ConnectionPhase.Idle or ConnectionPhase.Error or ConnectionPhase.Scanning)
            _client.Identify(device.Address);

        return root;
    }

    private UIElement BuildConnecting()
    {
        var root = new DockPanel();
        var title = TitleBar("Подключение", () => { _client.Disconnect(); Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var cancel = Secondary("Отменить", () => { _client.Disconnect(); Render(); });
        DockPanel.SetDock(cancel, Dock.Bottom);
        root.Children.Add(cancel);

        var center = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(24),
        };
        center.Children.Add(new ProgressBar { IsIndeterminate = true, Width = 180, Height = 4 });
        center.Children.Add(new TextBlock
        {
            Text = $"Подключение к\n{S.SelectedDevice?.UserName ?? S.SelectedDevice?.AdvertisedName ?? "устройству"}...",
            Foreground = Brushes.White,
            FontSize = 18,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 30, 0, 0),
        });
        if (S.Error != null)
        {
            center.Children.Add(new TextBlock
            {
                Text = S.Error,
                Foreground = (Brush)FindResource("OrangeBrush"),
                TextAlignment = TextAlignment.Center,
                Margin = new Thickness(0, 18, 0, 0),
            });
        }
        root.Children.Add(center);
        return root;
    }

    private UIElement BuildLogin()
    {
        var root = new DockPanel();
        var title = TitleBar(S.Initialized ? "Вход" : "Первичная настройка");
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);

        var form = new StackPanel { Margin = new Thickness(18) };
        var target = S.SelectedDevice?.UserName ?? S.SelectedDevice?.AdvertisedName;
        if (!string.IsNullOrEmpty(target))
        {
            form.Children.Add(new TextBlock
            {
                Text = target,
                Foreground = (Brush)FindResource("MutedBrush"),
                FontSize = 13,
                Margin = new Thickness(0, 0, 0, 12),
            });
        }

        TextBox? nameBox = null;
        if (!S.Initialized)
        {
            form.Children.Add(Label("Имя устройства"));
            nameBox = new TextBox { Text = S.SetupName, Style = (Style)FindResource("DarkField") };
            nameBox.TextChanged += (_, __) => _client.UpdateSetupName(nameBox.Text);
            form.Children.Add(nameBox);
        }

        form.Children.Add(Label("Пароль"));
        var pass = new PasswordBox { Style = (Style)FindResource("DarkPassword") };
        // PasswordBox has no TextChanged binding equivalent for our state; sync on change.
        pass.PasswordChanged += (_, __) => _client.UpdateSetupPassword(pass.Password);
        form.Children.Add(pass);
        if (!S.Initialized)
        {
            form.Children.Add(new TextBlock
            {
                Text = "Не менее 8 символов, латинские буквы и цифры",
                Foreground = (Brush)FindResource("MutedBrush"),
                FontSize = 11,
                Margin = new Thickness(0, 4, 0, 0),
            });
            form.Children.Add(Label("Повторите пароль"));
            var repeat = new PasswordBox { Style = (Style)FindResource("DarkPassword") };
            repeat.PasswordChanged += (_, __) => _client.UpdateSetupRepeatPassword(repeat.Password);
            form.Children.Add(repeat);
        }

        if (S.Error != null)
        {
            form.Children.Add(new TextBlock
            {
                Text = S.Error,
                Foreground = (Brush)FindResource("OrangeBrush"),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 12, 0, 0),
            });
        }

        var submit = Primary(
            S.Initialized ? "Подключиться" : "Сохранить",
            () =>
            {
                if (S.Initialized) _client.Authenticate(pass.Password);
                else _client.Setup(nameBox?.Text ?? S.SetupName, pass.Password);
            },
            enabled: true);
        // Enable based on live password boxes.
        void SyncEnabled()
        {
            var ok = pass.Password.Length >= 8;
            if (!S.Initialized)
            {
                var n = nameBox?.Text?.Trim() ?? "";
                // Repeat is tracked via session state.
                ok = ok && !string.IsNullOrEmpty(n) && S.SetupRepeatPassword == pass.Password;
            }
            submit.IsEnabled = S.CredentialsReady && ok;
        }
        pass.PasswordChanged += (_, __) => SyncEnabled();
        if (nameBox != null) nameBox.TextChanged += (_, __) => SyncEnabled();
        SyncEnabled();

        DockPanel.SetDock(submit, Dock.Bottom);
        root.Children.Add(submit);
        root.Children.Add(new ScrollViewer { Content = form, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        return root;
    }

    private static TextBlock Label(string text) => new()
    {
        Text = text,
        Foreground = new SolidColorBrush(Color.FromRgb(0x91, 0xa2, 0xac)),
        FontSize = 12,
        Margin = new Thickness(0, 12, 0, 6),
    };

    private UIElement BuildOperation()
    {
        var mode = S.State?.Mode ?? DplsMode.Normal;
        var testActive = DplsModeInfo.Dangerous(mode);
        var root = new DockPanel();

        var header = new StackPanel { Margin = new Thickness(0, 8, 0, 8) };
        header.Children.Add(new TextBlock
        {
            Text = "Испытание",
            Foreground = Brushes.White,
            FontSize = 17,
            FontWeight = FontWeights.Medium,
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        header.Children.Add(new TextBlock
        {
            Text = DeviceName(),
            Foreground = (Brush)FindResource("MutedBrush"),
            FontSize = 12,
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);

        var action = testActive
            ? Primary("Вернуть в «Норма»", () => _client.ReturnToNormal(), S.ControlsEnabled, (Brush)FindResource("OrangeBrush"))
            : Primary("Провести испытание", () => { _pickingTest = true; Render(); }, S.ControlsEnabled);
        DockPanel.SetDock(action, Dock.Bottom);
        root.Children.Add(action);

        var cardContent = new StackPanel();
        cardContent.Children.Add(new TextBlock
        {
            Text = DplsModeInfo.Title(mode),
            FontSize = 22,
            FontWeight = FontWeights.SemiBold,
            Foreground = testActive ? (Brush)FindResource("OrangeBrush") : (Brush)FindResource("GreenBrush"),
        });
        if (!string.IsNullOrEmpty(DplsModeInfo.PortHint(mode)))
        {
            cardContent.Children.Add(new TextBlock
            {
                Text = DplsModeInfo.PortHint(mode),
                Foreground = (Brush)FindResource("MutedBrush"),
                FontSize = 12,
                Margin = new Thickness(0, 2, 0, 8),
            });
        }

        if (S.DeviceInfo?.AdcPresent != false)
        {
            cardContent.Children.Add(InfoRow(
                "Напряжение",
                S.State?.LineVoltageValid == true ? $"{S.State!.VoltageMv / 1000.0:0.0} В" : "—",
                S.State?.LineVoltageValid == true));
        }
        cardContent.Children.Add(InfoRow(
            "Питание",
            S.State?.PowerValid == true ? $"От {PowerSourceInfo.Title(S.State!.PowerSource)}" : "Не определён",
            S.State?.PowerValid == true));
        if (S.State?.ReserveValid == true && S.State.ReserveLow)
            cardContent.Children.Add(InfoRow("Заряд резерва", "Низкий", false));
        if (S.State?.AutoIsoValid == true && S.State.RealShort)
        {
            cardContent.Children.Add(new TextBlock
            {
                Text = "⚠ Автоизоляция реального КЗ",
                Foreground = (Brush)FindResource("OrangeBrush"),
                FontWeight = FontWeights.Medium,
                Margin = new Thickness(0, 8, 0, 0),
            });
        }
        if (testActive && S.State != null)
        {
            var elapsed = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - S.State.ReceivedAtMillis;
            var left = Math.Max(0, S.State.AutomaticReturnSeconds - (int)(elapsed / 1000));
            cardContent.Children.Add(new TextBlock
            {
                Text = $"До автовозврата: {left / 60:D2}:{left % 60:D2}",
                Foreground = (Brush)FindResource("OrangeBrush"),
                Margin = new Thickness(0, 12, 0, 0),
                FontSize = 16,
                FontWeight = FontWeights.Medium,
            });
            var effect = DplsModeInfo.ControllerEffect(mode);
            if (!string.IsNullOrEmpty(effect))
            {
                cardContent.Children.Add(new TextBlock
                {
                    Text = effect,
                    Foreground = (Brush)FindResource("MutedBrush"),
                    FontSize = 12,
                    Margin = new Thickness(0, 4, 0, 0),
                });
            }
        }

        if (S.Error != null)
        {
            cardContent.Children.Add(new TextBlock
            {
                Text = S.Error,
                Foreground = (Brush)FindResource("OrangeBrush"),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 8, 0, 0),
            });
        }

        root.Children.Add(Card(cardContent));
        return root;
    }

    private static Grid InfoRow(string title, string value, bool ok)
    {
        var g = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.Children.Add(new TextBlock { Text = title, Foreground = new SolidColorBrush(Color.FromRgb(0x91, 0xa2, 0xac)), FontSize = 14 });
        var v = new TextBlock
        {
            Text = value,
            Foreground = ok ? new SolidColorBrush(Color.FromRgb(0x66, 0xc5, 0x3e)) : new SolidColorBrush(Color.FromRgb(0x91, 0xa2, 0xac)),
            FontSize = 14,
            FontWeight = FontWeights.Medium,
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        Grid.SetColumn(v, 1);
        g.Children.Add(v);
        return g;
    }

    private UIElement BuildTestPicker()
    {
        var root = new DockPanel();
        var title = TitleBar("Выбор испытания", () => { _pickingTest = false; Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var apply = Primary("Применить", () =>
        {
            _client.RequestMode(_chosenMode);
            _pickingTest = false;
            Render();
        });
        DockPanel.SetDock(apply, Dock.Bottom);
        root.Children.Add(apply);

        var list = new StackPanel { Margin = new Thickness(18, 0, 18, 0) };
        foreach (var mode in DplsModeInfo.DangerousModes)
        {
            var m = mode;
            var row = new Button
            {
                Background = Brushes.Transparent,
                BorderBrush = _chosenMode == m ? (Brush)FindResource("BlueBrush") : (Brush)FindResource("LineBrush"),
                BorderThickness = new Thickness(_chosenMode == m ? 1 : 0.5),
                Padding = new Thickness(14),
                Margin = new Thickness(0, 0, 0, 8),
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Cursor = System.Windows.Input.Cursors.Hand,
            };
            var sp = new StackPanel();
            sp.Children.Add(new TextBlock { Text = DplsModeInfo.Title(m), Foreground = Brushes.White, FontWeight = FontWeights.Medium });
            sp.Children.Add(new TextBlock { Text = DplsModeInfo.PortHint(m), Foreground = (Brush)FindResource("MutedBrush"), FontSize = 12 });
            row.Content = sp;
            row.Click += (_, __) => { _chosenMode = m; Render(); };
            list.Children.Add(row);
        }
        root.Children.Add(new ScrollViewer { Content = list });
        return root;
    }

    private UIElement BuildLog()
    {
        var root = new DockPanel();
        var header = new StackPanel { Margin = new Thickness(18, 8, 18, 8) };
        header.Children.Add(new TextBlock
        {
            Text = "Журнал",
            Foreground = Brushes.White,
            FontSize = 17,
            FontWeight = FontWeights.Medium,
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);

        var actions = new StackPanel();
        actions.Children.Add(Primary("Экспорт", () => { _page = Page.Export; Render(); }, S.EventLog.Count > 0));
        actions.Children.Add(Secondary("Обновить", () => _client.LoadEventLog()));
        DockPanel.SetDock(actions, Dock.Bottom);
        root.Children.Add(actions);

        if (S.LogProgress != null)
        {
            root.Children.Add(new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Center,
                Children =
                {
                    new ProgressBar { Value = S.LogProgress.Value * 100, Maximum = 100, Height = 6, Margin = new Thickness(40, 0, 40, 12) },
                    new TextBlock
                    {
                        Text = string.IsNullOrWhiteSpace(S.StatusText)
                            ? $"Загрузка журнала… {(int)(S.LogProgress.Value * 100)}%"
                            : S.StatusText,
                        Foreground = (Brush)FindResource("MutedBrush"),
                        HorizontalAlignment = HorizontalAlignment.Center,
                    },
                },
            });
            return root;
        }

        if (S.ControlsEnabled && S.EventLog.Count == 0 && S.LogProgress == null)
            _client.LoadEventLog();

        var firstSeq = S.EventLog.Where(e => e.Type == 1).Select(e => e.Sequence).DefaultIfEmpty(0u).Max();
        var list = new StackPanel { Margin = new Thickness(18, 0, 18, 0) };
        foreach (var e in S.EventLog)
        {
            var ts = DplsEventFormatting.Format(e, firstSeq, S.DeviceBootEpochSeconds);
            var row = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
            row.Children.Add(new TextBlock
            {
                Text = DplsEventFormatting.Title(e.Type, e.Parameter),
                Foreground = Brushes.White,
                TextWrapping = TextWrapping.Wrap,
            });
            row.Children.Add(new TextBlock
            {
                Text = $"#{e.Sequence}  {ts.Full}",
                Foreground = (Brush)FindResource("MutedBrush"),
                FontSize = 11,
            });
            list.Children.Add(row);
        }
        if (S.EventLog.Count == 0)
        {
            list.Children.Add(new TextBlock
            {
                Text = "Записей пока нет",
                Foreground = (Brush)FindResource("MutedBrush"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 40, 0, 0),
            });
        }
        root.Children.Add(new ScrollViewer { Content = list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        return root;
    }

    private UIElement BuildExport()
    {
        var root = new DockPanel();
        var title = TitleBar("Экспорт журнала", () => { _page = Page.Log; Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var body = new StackPanel { Margin = new Thickness(18), VerticalAlignment = VerticalAlignment.Center };
        body.Children.Add(Primary("Сохранить CSV", () => SaveText("dpls-log.csv", _client.EventLogCsv())));
        body.Children.Add(Primary("Сохранить TXT", () => SaveText("dpls-log.txt", _client.EventLogTxt())));
        root.Children.Add(body);
        return root;
    }

    private static void SaveText(string name, string content)
    {
        var dlg = new SaveFileDialog
        {
            FileName = name,
            Filter = name.EndsWith(".csv", StringComparison.OrdinalIgnoreCase)
                ? "CSV|*.csv|Все файлы|*.*"
                : "Текст|*.txt|Все файлы|*.*",
        };
        if (dlg.ShowDialog() == true)
            File.WriteAllText(dlg.FileName, content);
    }

    private UIElement BuildSettings()
    {
        var root = new DockPanel();
        var header = new TextBlock
        {
            Text = "Настройки",
            Foreground = Brushes.White,
            FontSize = 17,
            FontWeight = FontWeights.Medium,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 16, 0, 16),
        };
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);

        var list = new StackPanel { Margin = new Thickness(18) };
        list.Children.Add(SettingsRow("Имя устройства", () => { _page = Page.Name; Render(); }));
        list.Children.Add(SettingsRow("Пароль", () => { _page = Page.Password; Render(); }));
        list.Children.Add(SettingsRow("О устройстве", () => { _page = Page.About; _client.RequestDeviceInfo(); Render(); }));
        list.Children.Add(Primary("Отключиться", () =>
        {
            _client.Disconnect();
            _page = Page.Main;
            Render();
        }, color: (Brush)FindResource("OrangeBrush")));
        root.Children.Add(list);
        return root;
    }

    private Button SettingsRow(string title, Action action)
    {
        var btn = new Button
        {
            Content = title,
            Background = (Brush)FindResource("PanelBrush"),
            Foreground = Brushes.White,
            BorderBrush = (Brush)FindResource("LineBrush"),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(14),
            Margin = new Thickness(0, 0, 0, 8),
            HorizontalContentAlignment = HorizontalAlignment.Left,
            Cursor = System.Windows.Input.Cursors.Hand,
        };
        btn.Click += (_, __) => action();
        return btn;
    }

    private UIElement BuildName()
    {
        var root = new DockPanel();
        var title = TitleBar("Имя устройства", () => { _page = Page.Settings; _client.ClearSettingsOp(); Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var box = new TextBox
        {
            Text = S.DeviceInfo?.UserName ?? S.SelectedDevice?.UserName ?? S.SetupName,
            Style = (Style)FindResource("DarkField"),
            Margin = new Thickness(18),
        };
        var save = Primary("Сохранить", () =>
        {
            _client.SetDeviceName(box.Text);
            Render();
        });
        DockPanel.SetDock(save, Dock.Bottom);
        root.Children.Add(save);
        var body = new StackPanel();
        body.Children.Add(box);
        if (S.SettingsOp == SettingsOp.Done)
            body.Children.Add(Ok("Имя сохранено"));
        if (S.SettingsOp == SettingsOp.Failed && S.SettingsError != null)
            body.Children.Add(Err(S.SettingsError));
        root.Children.Add(body);
        return root;
    }

    private UIElement BuildPassword()
    {
        var root = new DockPanel();
        var title = TitleBar("Смена пароля", () => { _page = Page.Settings; _client.ClearSettingsOp(); Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var current = new PasswordBox { Style = (Style)FindResource("DarkPassword"), Margin = new Thickness(18, 8, 18, 8) };
        var next = new PasswordBox { Style = (Style)FindResource("DarkPassword"), Margin = new Thickness(18, 8, 18, 8) };
        var save = Primary("Сохранить", () =>
        {
            _client.ChangePassword(current.Password, next.Password);
            Render();
        });
        DockPanel.SetDock(save, Dock.Bottom);
        root.Children.Add(save);
        var body = new StackPanel();
        body.Children.Add(Label("Текущий пароль"));
        body.Children.Add(current);
        body.Children.Add(Label("Новый пароль (не менее 8 символов)"));
        body.Children.Add(next);
        if (S.SettingsOp == SettingsOp.Done)
            body.Children.Add(Ok("Пароль изменён"));
        if (S.SettingsOp == SettingsOp.Failed && S.SettingsError != null)
            body.Children.Add(Err(S.SettingsError));
        root.Children.Add(new ScrollViewer { Content = body });
        return root;
    }

    private UIElement BuildAbout()
    {
        var root = new DockPanel();
        var title = TitleBar("О устройстве", () => { _page = Page.Settings; Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var info = S.DeviceInfo;
        var body = new StackPanel { Margin = new Thickness(18) };
        body.Children.Add(AboutRow("Имя", info?.UserName ?? DeviceName()));
        body.Children.Add(AboutRow("ID", info?.ShortId ?? "—"));
        body.Children.Add(AboutRow("Прошивка", info?.FirmwareVersion ?? "—"));
        body.Children.Add(AboutRow("Протокол", info != null ? info.ProtocolVersion.ToString() : "—"));
        body.Children.Add(AboutRow("Железо", info != null ? info.HardwareRevision.ToString() : "—"));
        body.Children.Add(AboutRow("ADC", info == null ? "—" : info.AdcPresent ? (info.AdcCalibrated ? "есть, калиброван" : "есть") : "нет"));
        body.Children.Add(Primary("Обновить", () => _client.RequestDeviceInfo()));
        root.Children.Add(body);
        return root;
    }

    private static TextBlock AboutRow(string k, string v) => new()
    {
        Text = $"{k}: {v}",
        Foreground = Brushes.White,
        Margin = new Thickness(0, 0, 0, 10),
    };

    private static TextBlock Ok(string t) => new()
    {
        Text = t,
        Foreground = new SolidColorBrush(Color.FromRgb(0x66, 0xc5, 0x3e)),
        Margin = new Thickness(18, 8, 18, 0),
    };

    private static TextBlock Err(string t) => new()
    {
        Text = t,
        Foreground = new SolidColorBrush(Color.FromRgb(0xff, 0x6a, 0x2a)),
        TextWrapping = TextWrapping.Wrap,
        Margin = new Thickness(18, 8, 18, 0),
    };

    private string DeviceName()
    {
        if (!string.IsNullOrEmpty(S.DeviceInfo?.UserName)) return S.DeviceInfo!.UserName;
        return S.SelectedDevice?.UserName ?? S.SelectedDevice?.AdvertisedName ?? "Устройство";
    }

    private void NavMain_Click(object sender, RoutedEventArgs e)
    {
        _page = Page.Main;
        _pickingTest = false;
        Render();
    }

    private void NavLog_Click(object sender, RoutedEventArgs e)
    {
        _page = Page.Log;
        _pickingTest = false;
        Render();
    }

    private void NavSettings_Click(object sender, RoutedEventArgs e)
    {
        _page = Page.Settings;
        _pickingTest = false;
        Render();
    }

    private void ConfirmMode_Click(object sender, RoutedEventArgs e) => _client.ConfirmMode();
    private void CancelMode_Click(object sender, RoutedEventArgs e) => _client.CancelMode();
}
