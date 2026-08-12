using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Threading;
using TestDPLS.Models;

namespace TestDPLS.Preview;

public partial class MainWindow : Window
{
    private enum Page { Main, Log, Export, Settings, Name, Password, About }

    private readonly MockBleClient _client = new();
    private readonly DispatcherTimer _timer;
    private Page _page = Page.Main;
    private DplsMode _chosenMode = DplsMode.Short1;
    private DiscoveredDevice? _identifying;
    private bool _pickingTest;
    private readonly ContentControl _host = new();
    private readonly Border _nav;
    private readonly Border _confirm;
    private readonly TextBlock _confirmText = new() { TextWrapping = TextWrapping.Wrap, Foreground = Brushes.White };
    private readonly TextBlock _confirmHint = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _navMain;
    private readonly Button _navLog;
    private readonly Button _navSettings;

    public MainWindow()
    {
        AvaloniaXamlLoader.Load(this);
        Title = "Тест-ДПЛС (preview)";
        Width = 420;
        Height = 780;
        MinWidth = 360;
        MinHeight = 640;
        Background = Brush("BgBrush");
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        _navMain = NavBtn("Испытание", () => { _page = Page.Main; _pickingTest = false; Render(); });
        _navLog = NavBtn("Журнал", () => { _page = Page.Log; _pickingTest = false; Render(); });
        _navSettings = NavBtn("Настройки", () => { _page = Page.Settings; _pickingTest = false; Render(); });

        _nav = new Border
        {
            Background = Brush("NavBrush"),
            BorderBrush = Brush("LineBrush"),
            BorderThickness = new Thickness(0, 1, 0, 0),
            Height = 64,
            IsVisible = false,
            Child = new Grid
            {
                ColumnDefinitions = ColumnDefinitions.Parse("*,*,*"),
                Children =
                {
                    Col(_navMain, 0),
                    Col(_navLog, 1),
                    Col(_navSettings, 2),
                },
            },
        };

        var confirmPanel = new StackPanel { Spacing = 8 };
        confirmPanel.Children.Add(new TextBlock
        {
            Text = "Подтверждение",
            FontSize = 17,
            FontWeight = FontWeight.Medium,
            Foreground = Brushes.White,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 8),
        });
        confirmPanel.Children.Add(_confirmText);
        _confirmHint.Foreground = Brush("MutedBrush");
        confirmPanel.Children.Add(_confirmHint);
        confirmPanel.Children.Add(Primary("Подтвердить", () => _client.ConfirmMode(), color: Brush("OrangeBrush")));
        confirmPanel.Children.Add(Secondary("Отмена", () => _client.CancelMode()));

        _confirm = new Border
        {
            Background = SolidColorBrush.Parse("#E0071923"),
            IsVisible = false,
            Child = new Border
            {
                Background = Brush("PanelBrush"),
                BorderBrush = Brush("LineBrush"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Margin = new Thickness(24),
                Padding = new Thickness(18),
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                Child = confirmPanel,
            },
        };

        var root = new Grid { RowDefinitions = RowDefinitions.Parse("*,Auto") };
        Grid.SetRow(_host, 0);
        Grid.SetRow(_nav, 1);
        root.Children.Add(_host);
        root.Children.Add(_nav);
        var overlay = new Grid();
        overlay.Children.Add(root);
        overlay.Children.Add(_confirm);
        Content = overlay;

        _client.UiChanged += () => Dispatcher.UIThread.Post(Render);
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, __) =>
        {
            if (_client.Ui.State?.Mode is { } m && DplsModeInfo.Dangerous(m))
                Render();
        };
        _timer.Start();

        Opened += (_, __) =>
        {
            _client.StartScan();
            Render();
        };
    }

    private DplsUiState S => _client.Ui;

    private void Render()
    {
        var connected = S.SelectedDevice != null;
        var showConnecting = !S.Authenticated && (!S.CredentialsReady || !S.AwaitingUserPassword);
        var showLogin = !S.Authenticated && S.CredentialsReady && S.AwaitingUserPassword;
        var showTabs = S.Authenticated && _page is Page.Main or Page.Log or Page.Settings;
        _nav.IsVisible = showTabs;
        TintNav();

        if (S.PendingMode is { } pending)
        {
            _confirm.IsVisible = true;
            _confirmText.Text = pending == DplsMode.Normal
                ? "Вернуть устройство в режим «Норма»?"
                : $"Включить режим «{DplsModeInfo.Title(pending)}»?";
            _confirmHint.Text = pending == DplsMode.Normal
                ? "Испытание будет завершено."
                : $"{DplsModeInfo.PortHint(pending)}\n{DplsModeInfo.ControllerEffect(pending)}";
        }
        else _confirm.IsVisible = false;

        if (_identifying != null && !S.Authenticated)
        {
            _host.Content = BuildIdentify(_identifying);
            return;
        }
        if (!connected) { _host.Content = BuildDevices(); return; }
        if (showConnecting) { _host.Content = BuildConnecting(); return; }
        if (showLogin) { _host.Content = BuildLogin(); return; }
        if (_pickingTest) { _host.Content = BuildTestPicker(); return; }

        _host.Content = _page switch
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

    private void TintNav()
    {
        _navMain.Foreground = _page == Page.Main ? Brush("BlueBrush") : Brush("MutedBrush");
        _navLog.Foreground = _page == Page.Log ? Brush("BlueBrush") : Brush("MutedBrush");
        _navSettings.Foreground = _page == Page.Settings ? Brush("BlueBrush") : Brush("MutedBrush");
    }

    private Control BuildDevices()
    {
        var root = new DockPanel();
        var title = TitleBar("Устройства рядом");
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var refresh = Primary(S.Phase == ConnectionPhase.Scanning ? "Обновление..." : "↻  Обновить",
            () => _client.StartScan(), S.Phase != ConnectionPhase.Scanning);
        DockPanel.SetDock(refresh, Dock.Bottom);
        root.Children.Add(refresh);

        var body = new StackPanel { Margin = new Thickness(20, 0, 20, 0), Spacing = 8 };
        body.Children.Add(new TextBlock
        {
            Text = S.Phase == ConnectionPhase.Scanning ? "Ищем устройства по BLE..." : "Доступные устройства",
            Foreground = Brush("MutedBrush"),
            FontSize = 12,
        });
        body.Children.Add(new TextBlock
        {
            Text = "Preview: mock-устройства (пароль password1)",
            Foreground = Brush("OrangeBrush"),
            FontSize = 11,
        });

        if (S.Devices.Count == 0)
        {
            body.Children.Add(new TextBlock
            {
                Text = S.Phase == ConnectionPhase.Scanning ? "Идёт поиск устройств…" : "Устройства не найдены",
                Foreground = Brush("MutedBrush"),
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
                Margin = new Thickness(0, 40, 0, 0),
            });
        }
        else
        {
            foreach (var device in S.Devices)
            {
                var d = device;
                var btn = new Button
                {
                    Background = Brushes.Transparent,
                    BorderBrush = Brush("LineBrush"),
                    BorderThickness = new Thickness(0, 0, 0, 1),
                    HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
                    Padding = new Thickness(0, 12),
                    Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand),
                };
                var g = new Grid { ColumnDefinitions = ColumnDefinitions.Parse("*,Auto") };
                var left = new StackPanel();
                left.Children.Add(new TextBlock { Text = d.AdvertisedName, Foreground = Brushes.White });
                left.Children.Add(new TextBlock { Text = d.Address, Foreground = Brush("MutedBrush"), FontSize = 11 });
                var rssi = new TextBlock
                {
                    Text = $"{d.Rssi} дБм",
                    Foreground = Brush("GreenBrush"),
                    VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0),
                };
                Grid.SetColumn(rssi, 1);
                g.Children.Add(left);
                g.Children.Add(rssi);
                btn.Content = g;
                btn.Click += (_, __) => { _identifying = d; Render(); };
                body.Children.Add(btn);
            }
        }
        root.Children.Add(new ScrollViewer { Content = body });
        return root;
    }

    private Control BuildIdentify(DiscoveredDevice device)
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
                if (retry) _client.IdentifyRepair(device.Address);
                else { _client.ConfirmIdentifiedDevice(); _identifying = null; }
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
        root.Children.Add(new TextBlock
        {
            Text = status,
            Foreground = S.Error != null ? Brush("OrangeBrush") : Brushes.White,
            FontSize = 17,
            TextAlignment = TextAlignment.Center,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(24),
        });

        if (!S.IdentifyActive && S.Phase is ConnectionPhase.Idle or ConnectionPhase.Error or ConnectionPhase.Scanning)
            _client.Identify(device.Address);
        return root;
    }

    private Control BuildConnecting()
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
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Spacing = 16,
        };
        center.Children.Add(new ProgressBar { IsIndeterminate = true, Width = 180, Height = 4 });
        center.Children.Add(new TextBlock
        {
            Text = $"Подключение к\n{S.SelectedDevice?.AdvertisedName ?? "устройству"}...",
            Foreground = Brushes.White,
            FontSize = 18,
            TextAlignment = TextAlignment.Center,
        });
        if (S.Error != null)
            center.Children.Add(new TextBlock { Text = S.Error, Foreground = Brush("OrangeBrush"), TextAlignment = TextAlignment.Center });
        root.Children.Add(center);
        return root;
    }

    private Control BuildLogin()
    {
        var root = new DockPanel();
        var title = TitleBar(S.Initialized ? "Вход" : "Первичная настройка");
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);

        var form = new StackPanel { Margin = new Thickness(18), Spacing = 6 };
        form.Children.Add(new TextBlock
        {
            Text = S.SelectedDevice?.AdvertisedName ?? "",
            Foreground = Brush("MutedBrush"),
            FontSize = 13,
        });
        form.Children.Add(new TextBlock
        {
            Text = "Демо-пароль: password1",
            Foreground = Brush("OrangeBrush"),
            FontSize = 12,
        });

        TextBox? nameBox = null;
        if (!S.Initialized)
        {
            form.Children.Add(Label("Имя устройства"));
            nameBox = new TextBox { Text = S.SetupName, PlaceholderText = "Имя" };
            nameBox.TextChanged += (_, __) => _client.UpdateSetupName(nameBox.Text ?? "");
            form.Children.Add(nameBox);
        }

        form.Children.Add(Label("Пароль"));
        var pass = new TextBox { Text = S.SetupPassword, PasswordChar = '•', PlaceholderText = "Пароль" };
        pass.TextChanged += (_, __) => _client.UpdateSetupPassword(pass.Text ?? "");
        form.Children.Add(pass);

        TextBox? repeat = null;
        if (!S.Initialized)
        {
            form.Children.Add(Label("Повторите пароль"));
            repeat = new TextBox { PasswordChar = '•' };
            repeat.TextChanged += (_, __) => _client.UpdateSetupRepeatPassword(repeat.Text ?? "");
            form.Children.Add(repeat);
        }
        if (S.Error != null)
            form.Children.Add(new TextBlock { Text = S.Error, Foreground = Brush("OrangeBrush"), TextWrapping = TextWrapping.Wrap });

        var submit = Primary(S.Initialized ? "Подключиться" : "Сохранить", () =>
        {
            var password = pass.Text ?? "";
            _client.UpdateSetupPassword(password);
            if (S.Initialized) _client.Authenticate(password);
            else _client.Setup(nameBox?.Text ?? S.SetupName, password);
        });
        DockPanel.SetDock(submit, Dock.Bottom);
        root.Children.Add(submit);
        root.Children.Add(new ScrollViewer { Content = form });
        return root;
    }

    private Control BuildOperation()
    {
        var mode = S.State?.Mode ?? DplsMode.Normal;
        var testActive = DplsModeInfo.Dangerous(mode);
        var root = new DockPanel();
        var header = new StackPanel { Margin = new Thickness(0, 8) };
        header.Children.Add(new TextBlock
        {
            Text = "Испытание",
            Foreground = Brushes.White,
            FontSize = 17,
            FontWeight = FontWeight.Medium,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        header.Children.Add(new TextBlock
        {
            Text = S.DeviceInfo?.UserName ?? S.SelectedDevice?.AdvertisedName ?? "Устройство",
            Foreground = Brush("MutedBrush"),
            FontSize = 12,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);

        var action = testActive
            ? Primary("Вернуть в «Норма»", () => _client.ReturnToNormal(), S.ControlsEnabled, Brush("OrangeBrush"))
            : Primary("Провести испытание", () => { _pickingTest = true; Render(); }, S.ControlsEnabled);
        DockPanel.SetDock(action, Dock.Bottom);
        root.Children.Add(action);

        var card = new StackPanel { Spacing = 6 };
        card.Children.Add(new TextBlock
        {
            Text = DplsModeInfo.Title(mode),
            FontSize = 22,
            FontWeight = FontWeight.SemiBold,
            Foreground = testActive ? Brush("OrangeBrush") : Brush("GreenBrush"),
        });
        card.Children.Add(InfoRow("Напряжение", S.State?.LineVoltageValid == true ? $"{S.State!.VoltageMv / 1000.0:0.0} В" : "—"));
        card.Children.Add(InfoRow("Питание", S.State?.PowerValid == true ? $"От {PowerSourceInfo.Title(S.State!.PowerSource)}" : "—"));
        if (testActive && S.State != null)
        {
            var elapsed = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - S.State.ReceivedAtMillis;
            var left = Math.Max(0, S.State.AutomaticReturnSeconds - (int)(elapsed / 1000));
            card.Children.Add(new TextBlock
            {
                Text = $"До автовозврата: {left / 60:D2}:{left % 60:D2}",
                Foreground = Brush("OrangeBrush"),
                FontSize = 16,
                Margin = new Thickness(0, 8, 0, 0),
            });
        }
        if (S.Error != null)
            card.Children.Add(new TextBlock { Text = S.Error, Foreground = Brush("OrangeBrush"), TextWrapping = TextWrapping.Wrap });

        root.Children.Add(new Border
        {
            Background = Brush("PanelBrush"),
            BorderBrush = Brush("LineBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12),
            Margin = new Thickness(18, 8),
            Child = card,
        });
        return root;
    }

    private Control BuildTestPicker()
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
        var list = new StackPanel { Margin = new Thickness(18, 0), Spacing = 8 };
        foreach (var mode in DplsModeInfo.DangerousModes)
        {
            var m = mode;
            var row = new Button
            {
                Background = Brushes.Transparent,
                BorderBrush = _chosenMode == m ? Brush("BlueBrush") : Brush("LineBrush"),
                BorderThickness = new Thickness(_chosenMode == m ? 1 : 0.5),
                Padding = new Thickness(14),
                HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Left,
            };
            var sp = new StackPanel();
            sp.Children.Add(new TextBlock { Text = DplsModeInfo.Title(m), Foreground = Brushes.White, FontWeight = FontWeight.Medium });
            sp.Children.Add(new TextBlock { Text = DplsModeInfo.PortHint(m), Foreground = Brush("MutedBrush"), FontSize = 12 });
            row.Content = sp;
            row.Click += (_, __) => { _chosenMode = m; Render(); };
            list.Children.Add(row);
        }
        root.Children.Add(new ScrollViewer { Content = list });
        return root;
    }

    private Control BuildLog()
    {
        var root = new DockPanel();
        var header = new TextBlock
        {
            Text = "Журнал",
            Foreground = Brushes.White,
            FontSize = 17,
            FontWeight = FontWeight.Medium,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Margin = new Thickness(0, 16),
        };
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        var actions = new StackPanel();
        actions.Children.Add(Primary("Экспорт", () => { _page = Page.Export; Render(); }, S.EventLog.Count > 0));
        actions.Children.Add(Secondary("Обновить", () => _client.LoadEventLog()));
        DockPanel.SetDock(actions, Dock.Bottom);
        root.Children.Add(actions);

        if (S.ControlsEnabled && S.EventLog.Count == 0 && S.LogProgress == null)
            _client.LoadEventLog();

        if (S.LogProgress != null)
        {
            root.Children.Add(new StackPanel
            {
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                Children =
                {
                    new ProgressBar { Value = S.LogProgress.Value * 100, Maximum = 100, Height = 6, Margin = new Thickness(40, 0) },
                    new TextBlock
                    {
                        Text = string.IsNullOrWhiteSpace(S.StatusText)
                            ? $"Загрузка… {(int)(S.LogProgress.Value * 100)}%"
                            : S.StatusText,
                        Foreground = Brush("MutedBrush"),
                        HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
                        Margin = new Thickness(0, 12, 0, 0),
                    },
                },
            });
            return root;
        }

        var firstSeq = S.EventLog.Where(e => e.Type == 1).Select(e => e.Sequence).DefaultIfEmpty(0u).Max();
        var list = new StackPanel { Margin = new Thickness(18, 0), Spacing = 10 };
        foreach (var e in S.EventLog)
        {
            var ts = DplsEventFormatting.Format(e, firstSeq, S.DeviceBootEpochSeconds);
            var row = new StackPanel();
            row.Children.Add(new TextBlock { Text = DplsEventFormatting.Title(e.Type, e.Parameter), Foreground = Brushes.White, TextWrapping = TextWrapping.Wrap });
            row.Children.Add(new TextBlock { Text = $"#{e.Sequence}  {ts.Full}", Foreground = Brush("MutedBrush"), FontSize = 11 });
            list.Children.Add(row);
        }
        root.Children.Add(new ScrollViewer { Content = list });
        return root;
    }

    private Control BuildExport()
    {
        var root = new DockPanel();
        var title = TitleBar("Экспорт журнала", () => { _page = Page.Log; Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var body = new StackPanel
        {
            Margin = new Thickness(18),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Spacing = 8,
        };
        body.Children.Add(new TextBlock
        {
            Text = "CSV/TXT сохранение — в полной Windows-сборке через диалог файла.\nНиже — превью содержимого:",
            Foreground = Brush("MutedBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        body.Children.Add(new TextBox
        {
            Text = _client.EventLogTxt(),
            AcceptsReturn = true,
            Height = 320,
            IsReadOnly = true,
            TextWrapping = TextWrapping.Wrap,
        });
        root.Children.Add(body);
        return root;
    }

    private Control BuildSettings()
    {
        var root = new DockPanel();
        var header = new TextBlock
        {
            Text = "Настройки",
            Foreground = Brushes.White,
            FontSize = 17,
            FontWeight = FontWeight.Medium,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Margin = new Thickness(0, 16),
        };
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        var list = new StackPanel { Margin = new Thickness(18), Spacing = 8 };
        list.Children.Add(SettingsRow("Имя устройства", () => { _page = Page.Name; Render(); }));
        list.Children.Add(SettingsRow("Пароль", () => { _page = Page.Password; Render(); }));
        list.Children.Add(SettingsRow("О устройстве", () => { _page = Page.About; _client.RequestDeviceInfo(); Render(); }));
        list.Children.Add(Primary("Отключиться", () =>
        {
            _client.Disconnect();
            _page = Page.Main;
            Render();
        }, color: Brush("OrangeBrush")));
        root.Children.Add(list);
        return root;
    }

    private Control BuildName()
    {
        var root = new DockPanel();
        var title = TitleBar("Имя устройства", () => { _page = Page.Settings; _client.ClearSettingsOp(); Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var box = new TextBox
        {
            Text = S.DeviceInfo?.UserName ?? "Демо-ДПЛС",
            Margin = new Thickness(18),
        };
        var save = Primary("Сохранить", () => { _client.SetDeviceName(box.Text ?? ""); Render(); });
        DockPanel.SetDock(save, Dock.Bottom);
        root.Children.Add(save);
        var body = new StackPanel();
        body.Children.Add(box);
        if (S.SettingsOp == SettingsOp.Done)
            body.Children.Add(new TextBlock { Text = "Имя сохранено", Foreground = Brush("GreenBrush"), Margin = new Thickness(18, 8, 18, 0) });
        if (S.SettingsOp == SettingsOp.Failed)
            body.Children.Add(new TextBlock { Text = S.SettingsError ?? "Ошибка", Foreground = Brush("OrangeBrush"), Margin = new Thickness(18, 8, 18, 0) });
        root.Children.Add(body);
        return root;
    }

    private Control BuildPassword()
    {
        var root = new DockPanel();
        var title = TitleBar("Смена пароля", () => { _page = Page.Settings; _client.ClearSettingsOp(); Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var current = new TextBox { PasswordChar = '•', Margin = new Thickness(18, 8) };
        var next = new TextBox { PasswordChar = '•', Margin = new Thickness(18, 8) };
        var save = Primary("Сохранить", () =>
        {
            _client.ChangePassword(current.Text ?? "", next.Text ?? "");
            Render();
        });
        DockPanel.SetDock(save, Dock.Bottom);
        root.Children.Add(save);
        var body = new StackPanel();
        body.Children.Add(Label("Текущий пароль"));
        body.Children.Add(current);
        body.Children.Add(Label("Новый пароль"));
        body.Children.Add(next);
        root.Children.Add(body);
        return root;
    }

    private Control BuildAbout()
    {
        var root = new DockPanel();
        var title = TitleBar("О устройстве", () => { _page = Page.Settings; Render(); });
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);
        var info = S.DeviceInfo;
        var body = new StackPanel { Margin = new Thickness(18), Spacing = 10 };
        body.Children.Add(About("Имя", info?.UserName ?? "—"));
        body.Children.Add(About("ID", info?.ShortId ?? "—"));
        body.Children.Add(About("Прошивка", info?.FirmwareVersion ?? "—"));
        body.Children.Add(About("Протокол", info?.ProtocolVersion.ToString() ?? "—"));
        body.Children.Add(Primary("Обновить", () => _client.RequestDeviceInfo()));
        root.Children.Add(body);
        return root;
    }

    private static TextBlock About(string k, string v) => new() { Text = $"{k}: {v}", Foreground = Brushes.White };
    private static TextBlock Label(string t) => new() { Text = t, Foreground = SolidColorBrush.Parse("#91A2AC"), FontSize = 12, Margin = new Thickness(0, 8, 0, 4) };
    private static Grid InfoRow(string k, string v)
    {
        var g = new Grid { ColumnDefinitions = ColumnDefinitions.Parse("*,*") };
        g.Children.Add(new TextBlock { Text = k, Foreground = SolidColorBrush.Parse("#91A2AC") });
        var right = new TextBlock { Text = v, Foreground = SolidColorBrush.Parse("#66C53E"), HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right };
        Grid.SetColumn(right, 1);
        g.Children.Add(right);
        return g;
    }

    private Button SettingsRow(string title, Action action)
    {
        var btn = new Button
        {
            Content = title,
            Background = Brush("PanelBrush"),
            Foreground = Brushes.White,
            BorderBrush = Brush("LineBrush"),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(14),
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Left,
        };
        btn.Click += (_, __) => action();
        return btn;
    }

    private Control TitleBar(string title, Action? back = null)
    {
        var grid = new Grid { Margin = new Thickness(18, 8), MinHeight = 64 };
        if (back != null)
        {
            var b = new Button
            {
                Content = "‹",
                FontSize = 40,
                Foreground = Brushes.White,
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left,
            };
            b.Click += (_, __) => back();
            grid.Children.Add(b);
        }
        grid.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 17,
            FontWeight = FontWeight.Medium,
            Foreground = Brushes.White,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        });
        return grid;
    }

    private Button Primary(string title, Action action, bool enabled = true, IBrush? color = null)
    {
        var btn = new Button
        {
            Content = title,
            Background = color ?? Brush("BlueBrush"),
            Foreground = Brushes.White,
            FontSize = 16,
            FontWeight = FontWeight.SemiBold,
            Height = 52,
            Margin = new Thickness(18, 8),
            IsEnabled = enabled,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            CornerRadius = new CornerRadius(8),
            BorderThickness = new Thickness(0),
        };
        btn.Click += (_, __) => action();
        return btn;
    }

    private Button Secondary(string title, Action action)
    {
        var btn = new Button
        {
            Content = title,
            Background = Brushes.Transparent,
            Foreground = Brush("MutedBrush"),
            FontSize = 16,
            Height = 48,
            Margin = new Thickness(18, 0),
            BorderThickness = new Thickness(0),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        };
        btn.Click += (_, __) => action();
        return btn;
    }

    private static Button NavBtn(string title, Action action)
    {
        var btn = new Button
        {
            Content = title,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Foreground = SolidColorBrush.Parse("#91A2AC"),
        };
        btn.Click += (_, __) => action();
        return btn;
    }

    private static Control Col(Control c, int col)
    {
        Grid.SetColumn(c, col);
        return c;
    }

    private static IBrush Brush(string key) =>
        (IBrush)(Application.Current?.Resources[key] ?? Brushes.Gray)!;
}
