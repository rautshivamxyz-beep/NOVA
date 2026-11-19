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
            var model = Directory.GetFiles(modelDir, "*.gguf").FirstOrDefault();
            if (model is null)
            {
                MessageBox.Show(this, "Put one .gguf model file in the models folder beside NOVA.exe, then reopen NOVA.", "NOVA model required");
                Close(); return;
            }
            var enginePath = Path.Combine(root, "llama-server.exe");
            if (!File.Exists(enginePath)) throw new FileNotFoundException("The local NOVA inference engine is missing.", enginePath);

            status.Text = "Starting local model engine…";
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
            _ = Task.Run(async () =>
            {
                var log = Path.Combine(root, "nova-engine.log");
                await File.WriteAllTextAsync(log, "NOVA local engine log" + Environment.NewLine);
                while (!engine.HasExited)
                {
                    var line = await engine.StandardError.ReadLineAsync();
                    if (line is null) break;
                    await File.AppendAllTextAsync(log, line + Environment.NewLine);
                }
            });

            var ready = false;
            for (var i = 0; i < 240; i++)
            {
                if (engine.HasExited) throw new InvalidOperationException("The local model engine exited. See nova-engine.log.");
                try { using var response = await http.GetAsync($"http://127.0.0.1:{Port}/health"); if (response.IsSuccessStatusCode) { ready = true; break; } }
                catch { }
                await Task.Delay(1000);
            }
            if (!ready) throw new TimeoutException("NOVA did not finish loading within four minutes. Check nova-engine.log.");

            status.Text = "NOVA is running locally • Offline mode";
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

    private void StopEngine()
    {
        try { if (engine is { HasExited: false }) { engine.Kill(true); engine.WaitForExit(3000); } } catch { }
        http.Dispose();
    }
}