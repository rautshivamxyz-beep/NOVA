using System.Diagnostics;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace NovaDesktop;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new NovaWindow());
    }
}

internal sealed class NovaWindow : Form
{
    private readonly WebView2 web = new() { Dock = DockStyle.Fill };
    private readonly Label status = new() { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(12, 0, 0, 0) };
    private readonly Button updateButton = new() { Text = "Check for updates", AutoSize = true, Dock = DockStyle.Right, Margin = new Padding(6) };
    private Process? engine;
    private readonly string root = AppContext.BaseDirectory;
    private readonly string dataRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NOVA");
    private const int Port = 18181;
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private static readonly Version CurrentVersion = Assembly.GetExecutingAssembly().GetName().Version ?? new Version(1, 0, 0);

    public NovaWindow()
    {
        Text = "NOVA";
        Width = 1180; Height = 800; MinimumSize = new Size(760, 560);
        StartPosition = FormStartPosition.CenterScreen;
        var toolbar = new Panel { Dock = DockStyle.Top, Height = 34 };
        toolbar.Controls.Add(status);
        toolbar.Controls.Add(updateButton);
        Controls.Add(web); Controls.Add(toolbar);
        updateButton.Click += async (_, _) => await CheckForUpdates(true);
        FormClosing += (_, _) => StopEngine();
        Shown += async (_, _) => await StartNOVA();
    }

    private async Task StartNOVA()
    {
        try
        {
            var modelDir = Path.Combine(dataRoot, "models");
            Directory.CreateDirectory(modelDir);
            MigrateLegacyModels(modelDir);
            var model = ChooseModel(modelDir);
            if (model is null) { Close(); return; }

            var enginePath = Path.Combine(root, "llama-server.exe");
            if (!File.Exists(enginePath)) throw new FileNotFoundException("The local NOVA inference engine is missing.", enginePath);
            status.Text = $"Loading {Path.GetFileName(model)}…";
            engine = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = enginePath, WorkingDirectory = root, UseShellExecute = false,
                    CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
                    Arguments = $"-m \"{model}\" --alias NOVA --path \"{Path.Combine(root, "nova-web")}\" -c 4096 --port {Port} --host 127.0.0.1 -ngl 99",
                    RedirectStandardError = true
                },
                EnableRaisingEvents = true
            };
            engine.Start();
            var runningEngine = engine;
            _ = Task.Run(async () =>
            {
                var log = Path.Combine(dataRoot, "nova-engine.log");
                await File.WriteAllTextAsync(log, "NOVA local engine log" + Environment.NewLine);
                while (!runningEngine.HasExited)
                {
                    var line = await runningEngine.StandardError.ReadLineAsync();
                    if (line is null) break;
                    await File.AppendAllTextAsync(log, line + Environment.NewLine);
                }
            });

            var ready = false;
            for (var i = 0; i < 240; i++)
            {
                if (engine.HasExited) throw new InvalidOperationException($"The local model engine exited. See {Path.Combine(dataRoot, "nova-engine.log")}.");
                try { using var response = await http.GetAsync($"http://127.0.0.1:{Port}/health"); if (response.IsSuccessStatusCode) { ready = true; break; } }
                catch (HttpRequestException) { }
                catch (TaskCanceledException) { }
                await Task.Delay(1000);
            }
            if (!ready) throw new TimeoutException($"NOVA did not finish loading within four minutes. Check {Path.Combine(dataRoot, "nova-engine.log")}.");

            status.Text = $"NOVA is running locally • {Path.GetFileName(model)} • Offline mode";
            await web.EnsureCoreWebView2Async();
            web.CoreWebView2.Settings.AreDevToolsEnabled = false;
            web.CoreWebView2.Settings.IsStatusBarEnabled = false;
            web.CoreWebView2.NavigationStarting += (_, e) =>
            {
                if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri) || uri.Host != "127.0.0.1" || uri.Port != Port) e.Cancel = true;
            };
            web.Source = new Uri($"http://127.0.0.1:{Port}");
        }
        catch (Exception ex)
        {
            status.Text = "NOVA could not start.";
            MessageBox.Show(this, ex.Message, "NOVA startup error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
        }
    }

    private void MigrateLegacyModels(string destination)
    {
        var legacy = Path.Combine(root, "models");
        if (!Directory.Exists(legacy)) return;
        foreach (var file in Directory.EnumerateFiles(legacy, "*.gguf"))
        {
            var target = Path.Combine(destination, Path.GetFileName(file));
            if (!File.Exists(target)) File.Copy(file, target);
        }
    }

    private async Task CheckForUpdates(bool interactive)
    {
        updateButton.Enabled = false;
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            client.DefaultRequestHeaders.UserAgent.ParseAdd("NOVA-Windows-Desktop");
            using var response = await client.GetAsync("https://api.github.com/repos/rautshivamxyz-beep/NOVA/releases/latest");
            if (response.StatusCode == System.Net.HttpStatusCode.NotFound)
                throw new InvalidOperationException("No published NOVA desktop release was found yet.");
            response.EnsureSuccessStatusCode();
            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            var release = doc.RootElement;
            var tag = release.GetProperty("tag_name").GetString() ?? "";
            var versionText = tag.TrimStart('v', 'V');
            if (!Version.TryParse(versionText, out var latest))
                throw new InvalidOperationException($"The latest release tag '{tag}' is not a valid version.");
            if (latest <= CurrentVersion)
            {
                if (interactive) MessageBox.Show(this, $"NOVA is up to date. Installed version: {CurrentVersion}.", "NOVA updates", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            var asset = release.GetProperty("assets").EnumerateArray()
                .FirstOrDefault(a => string.Equals(a.GetProperty("name").GetString(), "NOVA-Setup.exe", StringComparison.OrdinalIgnoreCase));
            if (asset.ValueKind == JsonValueKind.Undefined)
                throw new InvalidOperationException($"Version {latest} is available, but its NOVA-Setup.exe asset is missing.");
            var url = asset.GetProperty("browser_download_url").GetString();
            if (string.IsNullOrWhiteSpace(url) || !Uri.TryCreate(url, UriKind.Absolute, out var downloadUri) || downloadUri.Host != "github.com")
                throw new InvalidOperationException("The release installer URL is invalid.");

            var answer = MessageBox.Show(this, $"NOVA {latest} is available (installed: {CurrentVersion}).\n\nDownload and install the update now? Your chats, settings, and models are stored separately and will be preserved.", "NOVA update available", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
            if (answer != DialogResult.Yes) return;

            var tempInstaller = Path.Combine(Path.GetTempPath(), $"NOVA-Setup-{latest}.exe");
            using (var download = await client.GetAsync(downloadUri, HttpCompletionOption.ResponseHeadersRead))
            {
                download.EnsureSuccessStatusCode();
                await using var input = await download.Content.ReadAsStreamAsync();
                await using var output = new FileStream(tempInstaller, FileMode.Create, FileAccess.Write, FileShare.None);
                await input.CopyToAsync(output);
            }
            var start = new ProcessStartInfo(tempInstaller, "/SILENT /SUPPRESSMSGBOXES /NORESTART")
            {
                UseShellExecute = true
            };
            Process.Start(start);
            Close();
        }
        catch (Exception ex)
        {
            if (interactive) MessageBox.Show(this, ex.Message, "NOVA update check failed", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally { updateButton.Enabled = true; }
    }

    private string? ChooseModel(string modelDir)
    {
        using var picker = new ModelPicker(modelDir);
        return picker.ShowDialog(this) == DialogResult.OK ? picker.SelectedModel : null;
    }

    private void StopEngine()
    {
        try { if (engine is { HasExited: false }) { engine.Kill(true); engine.WaitForExit(3000); } } catch { }
        http.Dispose();
    }
}

internal sealed class ModelPicker : Form
{
    private readonly string modelDirectory;
    private readonly ComboBox models = new() { DropDownStyle = ComboBoxStyle.DropDownList, Left = 20, Top = 58, Width = 480 };
    private readonly Label info = new() { Left = 20, Top = 20, Width = 480, Height = 30 };
    private readonly Button openFolder = new() { Left = 20, Top = 112, Width = 150, Height = 36, Text = "Open models folder" };
    private readonly Button refresh = new() { Left = 180, Top = 112, Width = 100, Height = 36, Text = "Refresh" };
    private readonly Button load = new() { Left = 400, Top = 160, Width = 100, Height = 36, Text = "Load model", DialogResult = DialogResult.OK };
    private readonly Button cancel = new() { Left = 290, Top = 160, Width = 100, Height = 36, Text = "Cancel", DialogResult = DialogResult.Cancel };
    public string? SelectedModel { get; private set; }

    public ModelPicker(string directory)
    {
        modelDirectory = directory;
        Text = "NOVA — Select model"; FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent; MaximizeBox = false; MinimizeBox = false;
        ClientSize = new Size(520, 215);
        Controls.AddRange([info, models, openFolder, refresh, load, cancel]);
        AcceptButton = load; CancelButton = cancel;
        openFolder.Click += (_, _) => Process.Start(new ProcessStartInfo { FileName = modelDirectory, UseShellExecute = true });
        refresh.Click += (_, _) => RefreshModels();
        load.Click += (_, _) => SelectedModel = models.SelectedItem is ModelEntry entry ? entry.FullPath : null;
        RefreshModels();
    }

    private void RefreshModels()
    {
        var selected = models.SelectedItem as ModelEntry;
        models.Items.Clear();
        foreach (var file in Directory.EnumerateFiles(modelDirectory, "*.gguf").OrderBy(file => Path.GetFileName(file), StringComparer.OrdinalIgnoreCase))
            models.Items.Add(new ModelEntry(file));
        if (models.Items.Count > 0) models.SelectedIndex = 0;
        if (selected is not null)
            for (var i = 0; i < models.Items.Count; i++)
                if (((ModelEntry)models.Items[i]!).FullPath.Equals(selected.FullPath, StringComparison.OrdinalIgnoreCase)) models.SelectedIndex = i;
        info.Text = models.Items.Count == 0 ? "No models found. Open this folder, paste a .gguf file, then click Refresh."
            : $"{models.Items.Count} model(s) found. Select one, then click Load model.";
        load.Enabled = models.Items.Count > 0;
    }

    private sealed class ModelEntry(string fullPath)
    {
        public string FullPath { get; } = fullPath;
        public override string ToString() => Path.GetFileName(FullPath);
    }
}
