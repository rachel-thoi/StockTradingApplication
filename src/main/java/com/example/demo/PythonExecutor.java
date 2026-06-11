package com.example.demo;


import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class PythonExecutor {

    private static final String PYTHON_SCRIPT_NAME = "options_analysis.py";
    private static final String BAYESIAN_SCRIPT_NAME = "bayesian_forecast.py";
    private static final int TIMEOUT_SECONDS = 120;
    private final String pythonCommand;

    public PythonExecutor() {
        this.pythonCommand = detectPythonCommand();
        System.out.println("Detected Python command: " + this.pythonCommand);
    }

    public String executePythonScript(String stockSymbol, String expirationDate,
                                      String optionType, double riskFreeRate,
                                      double volatility) throws Exception {

        String scriptPath = extractPythonScript();
        String outputDir = System.getProperty("java.io.tmpdir");

        System.out.println("Script path: " + scriptPath);

        return runScript(
                scriptPath,
                stockSymbol,
                expirationDate,
                optionType,
                String.valueOf(riskFreeRate),
                String.valueOf(volatility),
                outputDir
        );
    }

    public String executeBayesianForecast(String stockSymbol) throws Exception {
        String scriptPath = extractScript(BAYESIAN_SCRIPT_NAME);
        if (scriptPath == null) {
            throw new FileNotFoundException(
                    BAYESIAN_SCRIPT_NAME + " not found in application resources");
        }
        String outputDir = System.getProperty("java.io.tmpdir");

        System.out.println("Script path: " + scriptPath);

        return runScript(scriptPath, stockSymbol, outputDir);
    }

    private String runScript(String scriptPath, String... args) throws Exception {
        String outputDir = System.getProperty("java.io.tmpdir");

        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(pythonCommand);
        command.add(scriptPath);
        command.addAll(java.util.Arrays.asList(args));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(outputDir));
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.environment().put("PYTHONUNBUFFERED", "1");

        StringBuilder output = new StringBuilder();

        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Python script timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("Python error:\n" + output);
        }

        return output.toString();
    }

    private String extractPythonScript() throws IOException {
        String scriptPath = extractScript(PYTHON_SCRIPT_NAME);
        if (scriptPath == null) {
            // Create embedded script if not found
            System.out.println("Script not found in resources, using embedded script");
            return createEmbeddedScript();
        }
        return scriptPath;
    }

    private String extractScript(String scriptName) throws IOException {
        // Try to find the script in resources
        String[] paths = {
                "/com/example/demo/" + scriptName,
                "/" + scriptName,
                scriptName,
                "com/example/demo/" + scriptName
        };

        InputStream stream = null;
        for (String path : paths) {
            stream = getClass().getResourceAsStream(path);
            if (stream == null) {
                stream = getClass().getClassLoader().getResourceAsStream(
                        path.startsWith("/") ? path.substring(1) : path
                );
            }
            if (stream != null) {
                System.out.println("Found script at: " + path);
                break;
            }
        }

        if (stream == null) {
            return null;
        }

        Path tempScript = Files.createTempFile(
                scriptName.replace(".py", "") + "_", ".py");
        Files.copy(stream, tempScript, StandardCopyOption.REPLACE_EXISTING);
        stream.close();

        tempScript.toFile().deleteOnExit();
        return tempScript.toString();
    }

    private String createEmbeddedScript() throws IOException {
        String scriptContent = """
                #!/usr/bin/env python3
                import sys
                import os
                import numpy as np
                import yfinance as yf
                import matplotlib
                matplotlib.use('Agg')
                import matplotlib.pyplot as plt
                from scipy.stats import norm
                from datetime import datetime
                import warnings
                warnings.filterwarnings('ignore')
                
                def black_scholes(S, K, T, r, sigma, option_type='call'):
                    if T <= 0:
                        return max(S - K, 0) if option_type == 'call' else max(K - S, 0)
                    d1 = (np.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * np.sqrt(T))
                    d2 = d1 - sigma * np.sqrt(T)
                    if option_type == 'call':
                        return S * norm.cdf(d1) - K * np.exp(-r * T) * norm.cdf(d2)
                    return K * np.exp(-r * T) * norm.cdf(-d2) - S * norm.cdf(-d1)
                
                def calculate_greeks(S, K, T, r, sigma, option_type='call'):
                    if T <= 0:
                        return {'delta': 0, 'gamma': 0, 'theta': 0, 'vega': 0, 'rho': 0}
                    d1 = (np.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * np.sqrt(T))
                    d2 = d1 - sigma * np.sqrt(T)
                    delta = norm.cdf(d1) if option_type == 'call' else norm.cdf(d1) - 1
                    gamma = norm.pdf(d1) / (S * sigma * np.sqrt(T))
                    theta_term1 = -(S * norm.pdf(d1) * sigma) / (2 * np.sqrt(T))
                    if option_type == 'call':
                        theta = (theta_term1 - r * K * np.exp(-r * T) * norm.cdf(d2)) / 365
                    else:
                        theta = (theta_term1 + r * K * np.exp(-r * T) * norm.cdf(-d2)) / 365
                    vega = S * np.sqrt(T) * norm.pdf(d1) / 100
                    if option_type == 'call':
                        rho = K * T * np.exp(-r * T) * norm.cdf(d2) / 100
                    else:
                        rho = -K * T * np.exp(-r * T) * norm.cdf(-d2) / 100
                    return {'delta': delta, 'gamma': gamma, 'theta': theta, 'vega': vega, 'rho': rho}
                
                def get_stock_data(symbol):
                    ticker = yf.Ticker(symbol)
                    info = ticker.info
                    price = info.get('currentPrice') or info.get('regularMarketPrice')
                    if price is None:
                        hist = ticker.history(period='1d')
                        if not hist.empty:
                            price = hist['Close'].iloc[-1]
                        else:
                            raise ValueError(f"Could not fetch price for {symbol}")
                    return {'symbol': symbol, 'name': info.get('shortName', symbol), 'price': price}
                
                def create_chart(stock_data, exp_date, option_type, r_rate, vol, output_path):
                    S = stock_data['price']
                    symbol = stock_data['symbol']
                    exp = datetime.strptime(exp_date, '%Y-%m-%d')
                    T = max((exp - datetime.now()).days / 365.0, 0.001)
                    r = r_rate / 100
                    sigma = vol / 100
                    
                    fig = plt.figure(figsize=(14, 10))
                    fig.suptitle(f'{symbol} Options Analysis\\nPrice: ${S:.2f} | Exp: {exp_date} | Days: {int(T*365)}',
                                 fontsize=14, fontweight='bold')
                    gs = fig.add_gridspec(2, 3, hspace=0.3, wspace=0.3)
                    
                    stock_prices = np.linspace(S * 0.5, S * 1.5, 100)
                    
                    # Plot 1: Option Price vs Stock Price
                    ax1 = fig.add_subplot(gs[0, 0])
                    opt_prices = [black_scholes(sp, S, T, r, sigma, option_type) for sp in stock_prices]
                    ax1.plot(stock_prices, opt_prices, 'b-', linewidth=2)
                    ax1.axvline(x=S, color='r', linestyle='--', label=f'Current: ${S:.2f}')
                    ax1.set_xlabel('Stock Price ($)')
                    ax1.set_ylabel('Option Price ($)')
                    ax1.set_title('Option Price vs Stock Price')
                    ax1.legend()
                    ax1.grid(True, alpha=0.3)
                    
                    # Plot 2: Time Decay
                    ax2 = fig.add_subplot(gs[0, 1])
                    times = np.linspace(T, 0.001, 50)
                    time_prices = [black_scholes(S, S, t, r, sigma, option_type) for t in times]
                    ax2.plot(times * 365, time_prices, 'g-', linewidth=2)
                    ax2.set_xlabel('Days to Expiration')
                    ax2.set_ylabel('Option Price ($)')
                    ax2.set_title('Time Decay')
                    ax2.grid(True, alpha=0.3)
                    ax2.invert_xaxis()
                    
                    # Plot 3: Volatility Impact
                    ax3 = fig.add_subplot(gs[0, 2])
                    vols = np.linspace(0.05, 1.0, 50)
                    vol_prices = [black_scholes(S, S, T, r, v, option_type) for v in vols]
                    ax3.plot(vols * 100, vol_prices, 'm-', linewidth=2)
                    ax3.axvline(x=sigma * 100, color='r', linestyle='--')
                    ax3.set_xlabel('Volatility (%)')
                    ax3.set_ylabel('Option Price ($)')
                    ax3.set_title('Volatility Impact')
                    ax3.grid(True, alpha=0.3)
                    
                    # Plot 4: Delta
                    ax4 = fig.add_subplot(gs[1, 0])
                    deltas = [calculate_greeks(sp, S, T, r, sigma, option_type)['delta'] for sp in stock_prices]
                    ax4.plot(stock_prices, deltas, 'c-', linewidth=2)
                    ax4.axvline(x=S, color='r', linestyle='--')
                    ax4.set_xlabel('Stock Price ($)')
                    ax4.set_ylabel('Delta')
                    ax4.set_title('Delta')
                    ax4.grid(True, alpha=0.3)
                    
                    # Plot 5: Gamma
                    ax5 = fig.add_subplot(gs[1, 1])
                    gammas = [calculate_greeks(sp, S, T, r, sigma, option_type)['gamma'] for sp in stock_prices]
                    ax5.plot(stock_prices, gammas, 'orange', linewidth=2)
                    ax5.axvline(x=S, color='r', linestyle='--')
                    ax5.set_xlabel('Stock Price ($)')
                    ax5.set_ylabel('Gamma')
                    ax5.set_title('Gamma')
                    ax5.grid(True, alpha=0.3)
                    
                    # Plot 6: Summary
                    ax6 = fig.add_subplot(gs[1, 2])
                    ax6.axis('off')
                    price = black_scholes(S, S, T, r, sigma, option_type)
                    greeks = calculate_greeks(S, S, T, r, sigma, option_type)
                    summary = f\"\"\"
                    SUMMARY
                    ══════════════════
                    Stock: {symbol}
                    Price: ${S:.2f}
                    
                    Option Type: {option_type.upper()}
                    Strike: ${S:.2f} (ATM)
                    Days to Expiry: {int(T * 365)}
                    
                    Risk-Free Rate: {r_rate:.2f}%
                    Volatility: {vol:.2f}%
                    
                    Option Price: ${price:.4f}
                    
                    GREEKS
                    ══════════════════
                    Delta: {greeks['delta']:.4f}
                    Gamma: {greeks['gamma']:.6f}
                    Theta: ${greeks['theta']:.4f}/day
                    Vega: ${greeks['vega']:.4f}/1%
                    Rho: ${greeks['rho']:.4f}/1%
                    \"\"\"
                    ax6.text(0.1, 0.95, summary, transform=ax6.transAxes, fontsize=10,
                             verticalalignment='top', fontfamily='monospace',
                             bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
                    
                    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
                    chart_path = os.path.join(output_path, 'options_chart.png')
                    plt.savefig(chart_path, dpi=150, bbox_inches='tight', facecolor='white')
                    plt.close()
                    return chart_path
                
                def main():
                    if len(sys.argv) != 7:
                        print("Usage: script.py <symbol> <exp_date> <type> <rate> <vol> <output_dir>")
                        sys.exit(1)
                    
                    symbol = sys.argv[1].upper()
                    exp_date = sys.argv[2]
                    option_type = sys.argv[3].lower()
                    r_rate = float(sys.argv[4])
                    vol = float(sys.argv[5])
                    output_dir = sys.argv[6]
                    
                    print(f"Analyzing {symbol}...")
                    stock_data = get_stock_data(symbol)
                    print(f"Current Price: ${stock_data['price']:.2f}")
                    
                    T = max((datetime.strptime(exp_date, '%Y-%m-%d') - datetime.now()).days / 365.0, 0.001)
                    price = black_scholes(stock_data['price'], stock_data['price'], T, r_rate/100, vol/100, option_type)
                    greeks = calculate_greeks(stock_data['price'], stock_data['price'], T, r_rate/100, vol/100, option_type)
                    
                    print(f"\\nOption Price: ${price:.4f}")
                    print(f"Delta: {greeks['delta']:.4f}")
                    print(f"Gamma: {greeks['gamma']:.6f}")
                    print(f"Theta: ${greeks['theta']:.4f}/day")
                    print(f"Vega: ${greeks['vega']:.4f}")
                    print(f"Rho: ${greeks['rho']:.4f}")
                    
                    chart_path = create_chart(stock_data, exp_date, option_type, r_rate, vol, output_dir)
                    print(f"\\nChart saved to: {chart_path}")
                    print("Analysis complete!")
                
                if __name__ == "__main__":
                    main()
                """;

        Path tempScript = Files.createTempFile("options_analysis_", ".py");
        Files.writeString(tempScript, scriptContent);
        tempScript.toFile().deleteOnExit();
        return tempScript.toString();
    }

    private String detectPythonCommand() {
        String[] commands = {"python", "python3", "py"};
        for (String cmd : commands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {}
        }
        return "python";
    }

    public boolean checkPythonDependencies() {
        String[] packages = {"numpy", "yfinance", "matplotlib", "scipy"};
        for (String pkg : packages) {
            try {
                ProcessBuilder pb = new ProcessBuilder(pythonCommand, "-c", "import " + pkg);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public String installDependencies() throws Exception {
        StringBuilder output = new StringBuilder();
        String[] packages = {"numpy", "yfinance", "matplotlib", "scipy"};

        for (String pkg : packages) {
            output.append("Installing ").append(pkg).append("...\n");
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "-m", "pip", "install", "--upgrade", pkg);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append("  ").append(line).append("\n");
                }
            }
            p.waitFor(120, TimeUnit.SECONDS);
        }
        return output.toString();
    }

    public String getPythonCommand() {
        return pythonCommand;
    }
}