#!/usr/bin/env python3
"""
Bayesian Forecast Script

Estimates, for the current (or next) trading day:
  1. P(Close < Open) -- the probability the stock closes below its opening price
  2. The predicted closing price

Model
-----
Direction (Beta-Bernoulli):
    Each trading day is a Bernoulli trial: "down day" if Close < Open.
    Starting from a uniform Beta(1, 1) prior, the posterior after observing
    the historical OHLC data is Beta(1 + #down, 1 + #up). The posterior mean
    is then updated with option open-interest evidence via a Bayes factor
    derived from the put/call open-interest ratio (PCR):

        BF = (PCR / NEUTRAL_PCR) ** PCR_EVIDENCE_WEIGHT
        posterior odds(down) = prior odds(down) * BF

    A PCR above the neutral level (heavier put positioning) shifts probability
    toward a down close; below it, toward an up close. NEUTRAL_PCR and
    PCR_EVIDENCE_WEIGHT are modeling assumptions, not fitted parameters.

Magnitude (Normal-Inverse-Gamma):
    Intraday log returns r = ln(Close / Open) are modeled as Normal with
    unknown mean and variance under a conjugate Normal-Inverse-Gamma prior.
    The posterior predictive is a Student-t; its mean gives the expected
    intraday return, and the predicted close is open * exp(E[r]).

Note: yfinance only provides a current snapshot of option open interest
(no historical OI series), so OI enters the model as evidence for today
rather than as a training series.

Usage: python bayesian_forecast.py <symbol> <output_dir> [period]
"""

import sys
import os
import numpy as np
import yfinance as yf
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy import stats
from datetime import datetime
import warnings
warnings.filterwarnings('ignore')

NEUTRAL_PCR = 0.7          # typical equity put/call OI ratio, treated as "no signal"
PCR_EVIDENCE_WEIGHT = 1.5  # exponent k in BF = (PCR / NEUTRAL_PCR) ** k
MAX_EXPIRATIONS = 3        # how many nearby expirations to aggregate OI over
MIN_HISTORY_DAYS = 30


def fetch_history(symbol, period):
    ticker = yf.Ticker(symbol)
    hist = ticker.history(period=period, interval='1d', auto_adjust=False)
    if hist.empty or len(hist) < MIN_HISTORY_DAYS:
        raise ValueError(
            f"Not enough historical data for {symbol} "
            f"(got {len(hist)} days, need at least {MIN_HISTORY_DAYS})"
        )
    return ticker, hist


def fetch_open_interest(ticker):
    """Aggregate call/put open interest over the nearest expirations.

    Returns None when the symbol has no listed options or OI is unavailable.
    """
    try:
        expirations = list(ticker.options)[:MAX_EXPIRATIONS]
    except Exception:
        return None
    if not expirations:
        return None

    call_oi = 0.0
    put_oi = 0.0
    nearest_chain = None
    for exp in expirations:
        try:
            chain = ticker.option_chain(exp)
        except Exception:
            continue
        call_oi += float(chain.calls['openInterest'].fillna(0).sum())
        put_oi += float(chain.puts['openInterest'].fillna(0).sum())
        if nearest_chain is None:
            nearest_chain = {
                'expiration': exp,
                'call_strikes': chain.calls['strike'].values,
                'call_oi': chain.calls['openInterest'].fillna(0).values,
                'put_strikes': chain.puts['strike'].values,
                'put_oi': chain.puts['openInterest'].fillna(0).values,
            }

    if call_oi <= 0 and put_oi <= 0:
        return None
    return {'call_oi': call_oi, 'put_oi': put_oi, 'nearest_chain': nearest_chain}


def direction_posterior(hist):
    """Beta-Bernoulli posterior over P(down day) from historical OHLC."""
    down_days = int((hist['Close'] < hist['Open']).sum())
    up_days = len(hist) - down_days
    alpha = 1.0 + down_days
    beta = 1.0 + up_days
    return alpha, beta, down_days, up_days


def apply_oi_evidence(p_down, oi):
    """Update P(down) with the put/call open-interest ratio via a Bayes factor."""
    if oi is None or oi['call_oi'] <= 0:
        return p_down, 1.0, None
    pcr = oi['put_oi'] / oi['call_oi']
    bayes_factor = (pcr / NEUTRAL_PCR) ** PCR_EVIDENCE_WEIGHT
    odds = (p_down / (1.0 - p_down)) * bayes_factor
    return odds / (1.0 + odds), bayes_factor, pcr


def return_posterior(hist):
    """Normal-Inverse-Gamma update on intraday log returns ln(Close/Open).

    Returns the Student-t posterior predictive parameters and the data.
    """
    returns = np.log(hist['Close'] / hist['Open']).dropna().values

    # Weak prior centered on zero intraday drift
    mu0, kappa0, a0, b0 = 0.0, 1.0, 2.0, 1e-4

    n = len(returns)
    r_bar = returns.mean()
    kappa_n = kappa0 + n
    mu_n = (kappa0 * mu0 + n * r_bar) / kappa_n
    a_n = a0 + n / 2.0
    b_n = (b0 + 0.5 * np.sum((returns - r_bar) ** 2)
           + kappa0 * n * (r_bar - mu0) ** 2 / (2.0 * kappa_n))

    df = 2.0 * a_n
    scale = np.sqrt(b_n * (kappa_n + 1.0) / (a_n * kappa_n))
    return mu_n, scale, df, returns


def create_chart(symbol, open_price, open_basis, alpha, beta, p_down_prior,
                 p_down, pcr, oi, mu_n, scale, df, returns, predicted_close,
                 output_dir):
    fig = plt.figure(figsize=(14, 10))
    fig.suptitle(
        f'{symbol} Bayesian Forecast\n'
        f'P(Close < Open) = {p_down * 100:.1f}%  |  '
        f'Predicted Close: ${predicted_close:.2f}',
        fontsize=14, fontweight='bold'
    )
    gs = fig.add_gridspec(2, 2, hspace=0.3, wspace=0.25)

    # 1. Posterior over P(down day)
    ax1 = fig.add_subplot(gs[0, 0])
    x = np.linspace(0.0, 1.0, 500)
    ax1.plot(x, stats.beta.pdf(x, 1, 1), 'gray', linestyle=':',
             label='Prior Beta(1, 1)')
    ax1.plot(x, stats.beta.pdf(x, alpha, beta), 'b-', linewidth=2,
             label=f'Posterior Beta({alpha:.0f}, {beta:.0f})')
    ax1.axvline(x=p_down_prior, color='b', linestyle='--', alpha=0.6,
                label=f'Historical: {p_down_prior * 100:.1f}%')
    ax1.axvline(x=p_down, color='r', linestyle='-',
                label=f'After OI evidence: {p_down * 100:.1f}%')
    ax1.set_xlabel('P(Close < Open)')
    ax1.set_ylabel('Density')
    ax1.set_title('Down-Day Probability (Beta-Bernoulli)')
    ax1.legend(loc='best', fontsize=8)
    ax1.grid(True, alpha=0.3)

    # 2. Intraday returns vs posterior predictive
    ax2 = fig.add_subplot(gs[0, 1])
    ax2.hist(returns * 100, bins=40, density=True, alpha=0.5, color='steelblue',
             label='Historical ln(Close/Open) (%)')
    r_grid = np.linspace(returns.min(), returns.max(), 500)
    ax2.plot(r_grid * 100, stats.t.pdf(r_grid, df, loc=mu_n, scale=scale) / 100,
             'r-', linewidth=2, label='Posterior predictive (Student-t)')
    ax2.axvline(x=mu_n * 100, color='g', linestyle='--',
                label=f'E[return]: {mu_n * 100:.3f}%')
    ax2.set_xlabel('Intraday Return (%)')
    ax2.set_ylabel('Density')
    ax2.set_title('Intraday Return Model (Normal-Inverse-Gamma)')
    ax2.legend(loc='best', fontsize=8)
    ax2.grid(True, alpha=0.3)

    # 3. Open interest by strike (nearest expiration)
    ax3 = fig.add_subplot(gs[1, 0])
    if oi is not None and oi['nearest_chain'] is not None:
        chain = oi['nearest_chain']
        lo, hi = open_price * 0.8, open_price * 1.2
        c_mask = (chain['call_strikes'] >= lo) & (chain['call_strikes'] <= hi)
        p_mask = (chain['put_strikes'] >= lo) & (chain['put_strikes'] <= hi)
        width = open_price * 0.004
        ax3.bar(chain['call_strikes'][c_mask] - width / 2,
                chain['call_oi'][c_mask], width=width,
                color='green', alpha=0.6, label='Call OI')
        ax3.bar(chain['put_strikes'][p_mask] + width / 2,
                chain['put_oi'][p_mask], width=width,
                color='red', alpha=0.6, label='Put OI')
        ax3.axvline(x=open_price, color='b', linestyle='--',
                    label=f'Open basis: ${open_price:.2f}')
        ax3.set_title(f"Open Interest by Strike (exp {chain['expiration']})")
        ax3.set_xlabel('Strike ($)')
        ax3.set_ylabel('Open Interest')
        ax3.legend(loc='best', fontsize=8)
        ax3.grid(True, alpha=0.3)
    else:
        ax3.axis('off')
        ax3.text(0.5, 0.5, 'No option open-interest data available',
                 ha='center', va='center', fontsize=12, color='gray')

    # 4. Summary
    ax4 = fig.add_subplot(gs[1, 1])
    ax4.axis('off')
    pcr_line = f'{pcr:.3f}' if pcr is not None else 'n/a (no OI data)'
    summary = f"""
    BAYESIAN FORECAST
    ═══════════════════════════
    Stock: {symbol}
    Open basis: ${open_price:.2f}
      ({open_basis})

    Direction
    ─────────────────────────
    Historical P(down): {p_down_prior * 100:.1f}%
    Put/Call OI ratio: {pcr_line}
    P(Close < Open): {p_down * 100:.1f}%

    Magnitude
    ─────────────────────────
    E[intraday return]: {mu_n * 100:.3f}%
    Predictive std: {scale * 100:.2f}%

    Predicted Close: ${predicted_close:.2f}
    """
    ax4.text(0.05, 0.95, summary, transform=ax4.transAxes, fontsize=10,
             verticalalignment='top', fontfamily='monospace',
             bbox=dict(boxstyle='round', facecolor='lightblue', alpha=0.4))

    plt.tight_layout(rect=[0, 0.03, 1, 0.93])
    chart_path = os.path.join(output_dir, 'bayesian_chart.png')
    plt.savefig(chart_path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close()
    return chart_path


def main():
    if len(sys.argv) not in (3, 4):
        print("Usage: python bayesian_forecast.py <symbol> <output_dir> [period]")
        print("Example: python bayesian_forecast.py AAPL /tmp 1y")
        sys.exit(1)

    symbol = sys.argv[1].upper()
    output_dir = sys.argv[2]
    period = sys.argv[3] if len(sys.argv) == 4 else '1y'

    print("=" * 50)
    print(f"Bayesian Forecast for {symbol}")
    print("=" * 50)

    try:
        print(f"Fetching {period} of daily OHLC data...")
        ticker, hist = fetch_history(symbol, period)

        # If today's (still-forming) bar is present, predict today's close from
        # today's open and exclude the incomplete bar from training. Otherwise
        # use the last close as a proxy for the next session's open.
        last_date = hist.index[-1].date()
        if last_date == datetime.now().date():
            open_price = float(hist['Open'].iloc[-1])
            open_basis = "today's open"
            train = hist.iloc[:-1]
        else:
            open_price = float(hist['Close'].iloc[-1])
            open_basis = "last close, proxy for next open"
            train = hist
        if len(train) < MIN_HISTORY_DAYS:
            raise ValueError("Not enough complete trading days to train on")

        print(f"Training days: {len(train)}")
        print(f"Open basis: ${open_price:.2f} ({open_basis})")

        alpha, beta, down_days, up_days = direction_posterior(train)
        p_down_prior = alpha / (alpha + beta)
        print(f"Down days: {down_days} | Up days: {up_days}")
        print(f"Historical P(Close < Open): {p_down_prior * 100:.1f}%")

        print("Fetching option chain open interest...")
        oi = fetch_open_interest(ticker)
        p_down, bayes_factor, pcr = apply_oi_evidence(p_down_prior, oi)
        if pcr is not None:
            print(f"Call OI: {oi['call_oi']:,.0f} | Put OI: {oi['put_oi']:,.0f}")
            print(f"Put/Call ratio: {pcr:.3f} (neutral = {NEUTRAL_PCR})")
            print(f"Bayes factor toward down close: {bayes_factor:.3f}")
        else:
            print("No option open-interest data; using historical prior only.")

        mu_n, scale, df, returns = return_posterior(train)
        predicted_close = open_price * float(np.exp(mu_n))

        print("-" * 50)
        print("RESULTS")
        print("-" * 50)
        print(f"P(Close < Open): {p_down * 100:.1f}%")
        print(f"Expected intraday return: {mu_n * 100:.3f}%")
        print(f"Predicted Close: ${predicted_close:.2f}")

        print("\nGenerating chart...")
        chart_path = create_chart(
            symbol, open_price, open_basis, alpha, beta, p_down_prior,
            p_down, pcr, oi, mu_n, scale, df, returns, predicted_close,
            output_dir
        )
        print(f"Chart saved to: {chart_path}")
        print("=" * 50)
        print("Forecast completed successfully!")
        print("=" * 50)

    except Exception as e:
        print(f"\nError during forecast: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
