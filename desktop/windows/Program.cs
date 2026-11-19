using System.Diagnostics;
using System.Net.Http;
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
    private readonly Label status = new() { Dock = DockStyle.Bottom, Height = 28, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(12, 0, 0, 0) };
    private Process? engine;
    private readonly string root = AppContext.BaseDirectory;
    private const int Port = 18181;
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(2) };

    public NovaWindow()
    {
        Text = "NOVA";
        Width = 1180; Height = 800; MinimumSize = new Size(760, 560);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(web); Controls.Add(status);
        FormClosing += (_, _) => StopEngine();
        Shown += async (_, _) => await StartNOVA();
    }

    private async Task StartNOVA()
    {
        try
        {
            var modelDir = Path.Combine(root, "models");
            Directory.CreateDirectory(modelDir);
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
                var log = Path.Combine(root, "nova-engine.log");
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
                if (engine.HasExited) throw new InvalidOperationException("The local model engine exited. See nova-engine.log.");
                try { using var response = await http.GetAsync($"http://127.0.0.1:{Port}/health"); if (response.IsSuccessStatusCode) { ready = true; break; } }
                catch (HttpRequestException) { }
                catch (TaskCanceledException) { }
                await Task.Delay(1000);
            }
            if (!ready) throw new TimeoutException("NOVA did not finish loading within four minutes. Check nova-engine.log.");

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
    private readonly Label info = new() { Left = 20, Top = 20, Width = 480, Height = 30, Text = "Choose a local GGUF model to run with NOVA." };
    private readonly Button openFolder = new() { Left = 20, Top = 112, Width = 150, Height = 36, Text = "Open models folder" };
    private readonly Button refresh = new() { Left = 180, Top = 112, Width = 100, Height = 36, Text = "Refresh" };
    private readonly Button load = new() { Left = 400, Top = 160, Width = 100, Height = 36, Text = "Load model", DialogResult = DialogResult.OK };
    private readonly Button cancel = new() { Left = 290, Top = 160, Width = 100, Height = 36, Text = "Cancel", DialogResult = DialogResult.Cancel };

    public string? SelectedModel { get; private set; }

    public ModelPicker(string directory)
    {
        modelDirectory = directory;
        Text = "NOVA — Select model";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        MaximizeBox = false; MinimizeBox = false;
        ClientSize = new Size(520, 215);
        Controls.AddRange([info, models, openFolder, refresh, load, cancel]);
        AcceptButton = load; CancelButton = cancel;
        openFolder.Click += (_, _) => OpenModelsFolder();
        refresh.Click += (_, _) => RefreshModels();
        load.Click += (_, _) => SelectedModel = models.SelectedItem is ModelEntry entry ? entry.FullPath : null;
        RefreshModels();
    }

    private void OpenModelsFolder()
    {
        Directory.CreateDirectory(modelDirectory);
        Process.Start(new ProcessStartInfo { FileName = modelDirectory, UseShellExecute = true });
    }

    private void RefreshModels()
    {
        var selected = models.SelectedItem as ModelEntry;
        models.Items.Clear();
        foreach (var file in Directory.EnumerateFiles(modelDirectory, "*.gguf", SearchOption.TopDirectoryOnly).OrderBy(Path.GetFileName, StringComparer.OrdinalIgnoreCase))
            models.Items.Add(new ModelEntry(file));
        if (models.Items.Count > 0) models.SelectedIndex = 0;
        if (selected is not null)
            for (var i = 0; i < models.Items.Count; i++)
                if (((ModelEntry)models.Items[i]!).FullPath.Equals(selected.FullPath, StringComparison.OrdinalIgnoreCase)) models.SelectedIndex = i;
        info.Text = models.Items.Count == 0
            ? "No models found. Open this folder, paste a .gguf file, then click Refresh."
            : $"{models.Items.Count} model(s) found. Select one, then click Load model.";
        load.Enabled = models.Items.Count > 0;
    }

    private sealed class ModelEntry(string fullPath)
    {
        public string FullPath { get; } = fullPath;
        public override string ToString() => Path.GetFileName(FullPath);
    }
}
