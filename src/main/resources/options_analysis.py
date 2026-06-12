#!/usr/bin/env python3
"""
Options Analysis Script
Performs Black-Scholes options pricing and generates visualization charts.
"""

import sys
import os
import numpy as np
import yfinance as yf
import matplotlib
matplotlib.use('Agg')  # Use non-interactive backend
import matplotlib.pyplot as plt
from matplotlib.figure import Figure
from scipy.stats import norm
from datetime import datetime, timedelta
import warnings
warnings.filterwarnings('ignore')


def black_scholes(S, K, T, r, sigma, option_type='call'):
    """
    Calculate Black-Scholes option price.

    Parameters:
    S: Current stock price
    K: Strike price
    T: Time to expiration (in years)
    r: Risk-free interest rate (as decimal)
    sigma: Volatility (as decimal)
    option_type: 'call' or 'put'

    Returns:
    Option price
    """
    if T <= 0:
        if option_type == 'call':
            return max(S - K, 0)
        else:
            return max(K - S, 0)

    d1 = (np.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * np.sqrt(T))
    d2 = d1 - sigma * np.sqrt(T)

    if option_type == 'call':
        price = S * norm.cdf(d1) - K * np.exp(-r * T) * norm.cdf(d2)
    else:
        price = K * np.exp(-r * T) * norm.cdf(-d2) - S * norm.cdf(-d1)

    return price


def calculate_greeks(S, K, T, r, sigma, option_type='call'):
    """
    Calculate option Greeks.

    Returns dict with delta, gamma, theta, vega, rho
    """
    if T <= 0:
        return {'delta': 0, 'gamma': 0, 'theta': 0, 'vega': 0, 'rho': 0}

    d1 = (np.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * np.sqrt(T))
    d2 = d1 - sigma * np.sqrt(T)

    # Delta
    if option_type == 'call':
        delta = norm.cdf(d1)
    else:
        delta = norm.cdf(d1) - 1

    # Gamma
    gamma = norm.pdf(d1) / (S * sigma * np.sqrt(T))

    # Theta (per day)
    theta_term1 = -(S * norm.pdf(d1) * sigma) / (2 * np.sqrt(T))
    if option_type == 'call':
        theta = (theta_term1 - r * K * np.exp(-r * T) * norm.cdf(d2)) / 365
    else:
        theta = (theta_term1 + r * K * np.exp(-r * T) * norm.cdf(-d2)) / 365

    # Vega (per 1% change in volatility)
    vega = S * np.sqrt(T) * norm.pdf(d1) / 100

    # Rho (per 1% change in interest rate)
    if option_type == 'call':
        rho = K * T * np.exp(-r * T) * norm.cdf(d2) / 100
    else:
        rho = -K * T * np.exp(-r * T) * norm.cdf(-d2) / 100

    return {
        'delta': delta,
        'gamma': gamma,
        'theta': theta,
        'vega': vega,
        'rho': rho
    }


def get_stock_data(symbol):
    """
    Fetch current stock data using yfinance.
    """
    try:
        ticker = yf.Ticker(symbol)
        info = ticker.info

        # Get current price
        current_price = info.get('currentPrice') or info.get('regularMarketPrice')

        if current_price is None:
            # Try to get from history
            hist = ticker.history(period='1d')
            if not hist.empty:
                current_price = hist['Close'].iloc[-1]
            else:
                raise ValueError(f"Could not fetch price for {symbol}")

        # Get additional info
        company_name = info.get('shortName', symbol)

        return {
            'symbol': symbol,
            'name': company_name,
            'price': current_price
        }
    except Exception as e:
        print(f"Error fetching stock data: {e}")
        raise


def fetch_market_options(symbol, expiration_date, option_type):
    """
    Fetch actual market option prices for the requested expiration.

    Uses the exact expiration when it is listed, otherwise the nearest one.
    The market price per strike is the bid/ask midpoint when both sides are
    quoted, falling back to the last traded price.

    Returns None when the symbol has no listed options or no usable quotes.
    """
    try:
        ticker = yf.Ticker(symbol)
        expirations = list(ticker.options)
        if not expirations:
            return None

        if expiration_date in expirations:
            exp = expiration_date
        else:
            target = datetime.strptime(expiration_date, '%Y-%m-%d')
            exp = min(expirations,
                      key=lambda e: abs(datetime.strptime(e, '%Y-%m-%d') - target))

        chain = ticker.option_chain(exp)
        quotes = chain.calls if option_type == 'call' else chain.puts

        strikes = quotes['strike'].values
        bid = quotes['bid'].fillna(0).values
        ask = quotes['ask'].fillna(0).values
        last = quotes['lastPrice'].fillna(0).values
        prices = np.where((bid > 0) & (ask > 0), (bid + ask) / 2.0, last)

        valid = prices > 0
        if not valid.any():
            return None

        exp_dt = datetime.strptime(exp, '%Y-%m-%d')
        T = max((exp_dt - datetime.now()).days / 365.0, 0.001)

        return {
            'expiration': exp,
            'T': T,
            'strikes': strikes[valid],
            'prices': prices[valid],
        }
    except Exception as e:
        print(f"Warning: could not fetch market option chain: {e}")
        return None


def create_analysis_chart(stock_data, expiration_date, option_type, risk_free_rate,
                          volatility, market, output_path):
    """
    Create comprehensive options analysis chart.
    """
    S = stock_data['price']
    symbol = stock_data['symbol']

    # Calculate time to expiration
    exp_date = datetime.strptime(expiration_date, '%Y-%m-%d')
    today = datetime.now()
    T = max((exp_date - today).days / 365.0, 0.001)

    r = risk_free_rate / 100  # Convert to decimal
    sigma = volatility / 100  # Convert to decimal

    # Create figure with subplots
    fig = plt.figure(figsize=(14, 10))
    fig.suptitle(f'{symbol} ({stock_data["name"]}) Options Analysis\n'
                 f'Current Price: ${S:.2f} | Expiration: {expiration_date} | '
                 f'Days to Expiry: {int(T * 365)}',
                 fontsize=14, fontweight='bold')

    # Create grid for subplots
    gs = fig.add_gridspec(2, 3, hspace=0.3, wspace=0.3)

    # 1. Black-Scholes predicted vs actual market option prices (by strike)
    ax1 = fig.add_subplot(gs[0, 0])
    stock_prices = np.linspace(S * 0.5, S * 1.5, 100)
    # Price the comparison at the expiration the market data is for, so the
    # model and the quotes describe the same contract
    T_cmp = market['T'] if market is not None else T
    strikes_grid = np.linspace(S * 0.5, S * 1.5, 100)
    model_prices = [black_scholes(S, k, T_cmp, r, sigma, option_type) for k in strikes_grid]
    ax1.plot(strikes_grid, model_prices, 'b-', linewidth=2, label='Black-Scholes (model)')
    if market is not None:
        window = (market['strikes'] >= S * 0.5) & (market['strikes'] <= S * 1.5)
        ax1.plot(market['strikes'][window], market['prices'][window], 'ro',
                 markersize=4, alpha=0.7, label=f"Market ({market['expiration']})")
    else:
        ax1.text(0.5, 0.9, 'Market option data unavailable', transform=ax1.transAxes,
                 ha='center', fontsize=9, color='gray')
    ax1.axvline(x=S, color='g', linestyle='--', alpha=0.7, label=f'Spot: ${S:.2f}')
    ax1.set_xlabel('Strike ($)')
    ax1.set_ylabel('Option Price ($)')
    ax1.set_title('Model vs Market Option Prices')
    ax1.legend(loc='best', fontsize=8)
    ax1.grid(True, alpha=0.3)

    # 2. Option Price vs Time to Expiration
    ax2 = fig.add_subplot(gs[0, 1])
    times = np.linspace(T, 0.001, 50)
    time_prices = [black_scholes(S, S, t, r, sigma, option_type) for t in times]
    days_to_exp = times * 365
    ax2.plot(days_to_exp, time_prices, 'g-', linewidth=2)
    ax2.axvline(x=T * 365, color='r', linestyle='--', label=f'Current: {int(T * 365)} days')
    ax2.set_xlabel('Days to Expiration')
    ax2.set_ylabel('Option Price ($)')
    ax2.set_title('Time Decay (Theta)')
    ax2.legend(loc='best', fontsize=8)
    ax2.grid(True, alpha=0.3)
    ax2.invert_xaxis()

    # 3. Option Price vs Volatility
    ax3 = fig.add_subplot(gs[0, 2])
    volatilities = np.linspace(0.05, 1.0, 50)
    vol_prices = [black_scholes(S, S, T, r, vol, option_type) for vol in volatilities]
    ax3.plot(volatilities * 100, vol_prices, 'm-', linewidth=2)
    ax3.axvline(x=sigma * 100, color='r', linestyle='--', label=f'Current: {sigma * 100:.1f}%')
    ax3.set_xlabel('Volatility (%)')
    ax3.set_ylabel('Option Price ($)')
    ax3.set_title('Volatility Impact (Vega)')
    ax3.legend(loc='best', fontsize=8)
    ax3.grid(True, alpha=0.3)

    # 4. Delta across stock prices
    ax4 = fig.add_subplot(gs[1, 0])
    deltas = [calculate_greeks(sp, S, T, r, sigma, option_type)['delta'] for sp in stock_prices]
    ax4.plot(stock_prices, deltas, 'c-', linewidth=2)
    ax4.axvline(x=S, color='r', linestyle='--', label=f'Current: ${S:.2f}')
    current_delta = calculate_greeks(S, S, T, r, sigma, option_type)['delta']
    ax4.axhline(y=current_delta, color='g', linestyle=':', alpha=0.5)
    ax4.set_xlabel('Stock Price ($)')
    ax4.set_ylabel('Delta')
    ax4.set_title(f'Delta (Current: {current_delta:.4f})')
    ax4.legend(loc='best', fontsize=8)
    ax4.grid(True, alpha=0.3)

    # 5. Gamma across stock prices
    ax5 = fig.add_subplot(gs[1, 1])
    gammas = [calculate_greeks(sp, S, T, r, sigma, option_type)['gamma'] for sp in stock_prices]
    ax5.plot(stock_prices, gammas, 'orange', linewidth=2)
    ax5.axvline(x=S, color='r', linestyle='--', label=f'Current: ${S:.2f}')
    current_gamma = calculate_greeks(S, S, T, r, sigma, option_type)['gamma']
    ax5.set_xlabel('Stock Price ($)')
    ax5.set_ylabel('Gamma')
    ax5.set_title(f'Gamma (Current: {current_gamma:.6f})')
    ax5.legend(loc='best', fontsize=8)
    ax5.grid(True, alpha=0.3)

    # 6. Summary text box
    ax6 = fig.add_subplot(gs[1, 2])
    ax6.axis('off')

    # Calculate current values
    current_price = black_scholes(S, S, T, r, sigma, option_type)
    greeks = calculate_greeks(S, S, T, r, sigma, option_type)

    summary_text = f"""
    SUMMARY
    ═══════════════════════════

    Stock: {symbol}
    Current Price: ${S:.2f}

    Option Details:
    ─────────────────────────
    Type: {option_type.upper()}
    Strike: ${S:.2f} (ATM)
    Expiration: {expiration_date}
    Days to Expiry: {int(T * 365)}

    Inputs:
    ─────────────────────────
    Risk-Free Rate: {risk_free_rate:.2f}%
    Volatility: {volatility:.2f}%

    Option Price: ${current_price:.4f}

    Greeks:
    ─────────────────────────
    Delta: {greeks['delta']:.4f}
    Gamma: {greeks['gamma']:.6f}
    Theta: ${greeks['theta']:.4f}/day
    Vega: ${greeks['vega']:.4f}/1% vol
    Rho: ${greeks['rho']:.4f}/1% rate
    """

    ax6.text(0.1, 0.95, summary_text, transform=ax6.transAxes,
             fontsize=10, verticalalignment='top', fontfamily='monospace',
             bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))

    # Save the figure
    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    chart_path = os.path.join(output_path, 'options_chart.png')
    plt.savefig(chart_path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close()

    return chart_path


def main():
    """
    Main function - entry point for the script.
    """
    if len(sys.argv) != 7:
        print("Usage: python options_analysis.py <symbol> <expiration_date> "
              "<option_type> <risk_free_rate> <volatility> <output_dir>")
        print("Example: python options_analysis.py AAPL 2024-03-15 call 5.0 25.0 /tmp")
        sys.exit(1)

    # Parse arguments
    symbol = sys.argv[1].upper()
    expiration_date = sys.argv[2]
    option_type = sys.argv[3].lower()
    risk_free_rate = float(sys.argv[4])
    volatility = float(sys.argv[5])
    output_dir = sys.argv[6]

    print(f"=" * 50)
    print(f"Options Analysis for {symbol}")
    print(f"=" * 50)
    print(f"Expiration Date: {expiration_date}")
    print(f"Option Type: {option_type.upper()}")
    print(f"Risk-Free Rate: {risk_free_rate}%")
    print(f"Historical Volatility: {volatility}%")
    print(f"Output Directory: {output_dir}")
    print(f"-" * 50)

    # Validate option type
    if option_type not in ['call', 'put']:
        print(f"Error: Invalid option type '{option_type}'. Must be 'call' or 'put'.")
        sys.exit(1)

    # Validate expiration date
    try:
        exp_date = datetime.strptime(expiration_date, '%Y-%m-%d')
        if exp_date <= datetime.now():
            print("Warning: Expiration date is in the past or today.")
    except ValueError:
        print(f"Error: Invalid date format '{expiration_date}'. Use YYYY-MM-DD.")
        sys.exit(1)

    try:
        # Fetch stock data
        print(f"\nFetching stock data for {symbol}...")
        stock_data = get_stock_data(symbol)
        print(f"Company: {stock_data['name']}")
        print(f"Current Price: ${stock_data['price']:.2f}")

        # Calculate option price
        T = max((exp_date - datetime.now()).days / 365.0, 0.001)
        r = risk_free_rate / 100
        sigma = volatility / 100
        S = stock_data['price']

        option_price = black_scholes(S, S, T, r, sigma, option_type)
        greeks = calculate_greeks(S, S, T, r, sigma, option_type)

        print(f"\n{'=' * 50}")
        print(f"RESULTS (ATM Option)")
        print(f"{'=' * 50}")
        print(f"Option Price: ${option_price:.4f}")
        print(f"\nGreeks:")
        print(f"  Delta: {greeks['delta']:.4f}")
        print(f"  Gamma: {greeks['gamma']:.6f}")
        print(f"  Theta: ${greeks['theta']:.4f} per day")
        print(f"  Vega:  ${greeks['vega']:.4f} per 1% volatility")
        print(f"  Rho:   ${greeks['rho']:.4f} per 1% interest rate")

        # Fetch actual market option prices for comparison with the model
        print(f"\nFetching market option chain...")
        market = fetch_market_options(symbol, expiration_date, option_type)
        if market is None:
            print("No market option data available; chart will show model prices only.")
        else:
            print(f"Market expiration used: {market['expiration']} "
                  f"({len(market['strikes'])} quoted strikes)")
            model_at_strikes = np.array([
                black_scholes(S, k, market['T'], r, sigma, option_type)
                for k in market['strikes']
            ])
            mae = np.mean(np.abs(model_at_strikes - market['prices']))
            print(f"Mean |model - market| across strikes: ${mae:.2f}")

        # Create analysis chart
        print(f"\nGenerating analysis chart...")
        chart_path = create_analysis_chart(
            stock_data, expiration_date, option_type,
            risk_free_rate, volatility, market, output_dir
        )
        print(f"Chart saved to: {chart_path}")
        print(f"\n{'=' * 50}")
        print("Analysis completed successfully!")
        print(f"{'=' * 50}")

    except Exception as e:
        print(f"\nError during analysis: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()