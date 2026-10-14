#!/usr/bin/env python3
# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Investment simulator for the Investment Sales Advisor skill (AI-262).

Reproduces the formulas of Investment Simulator V12 for Total Investment
(Promissory Note) and Total Investment +D60 (CEDE). Rates, tax parameters and the
campaign switch are read from ../assets/simulator-v12.json, never from the caller.

The advisor must take every figure from this script's output and never compute
figures itself. Output is one JSON object on stdout. A non-OK status means: do not
show figures; follow the message.

Usage:
    python3 simulate_investment.py --product promissory --amount 500000 --days 180
    python3 simulate_investment.py --product cede --amount 1000000 --maturity-date 2027-10-06
    python3 simulate_investment.py --product promissory --amount 50000 --days 30 --rate-only

`simulate()` is a pure function so a gateway or MCP tool can wrap it without the CLI.
"""

import argparse
import json
import re
import sys
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

DATA_FILE = Path(__file__).resolve().parent.parent / "assets" / "simulator-v12.json"
# Mexico has had no daylight saving time since 2022: Mexico City is always UTC-6.
MEXICO_CITY = timezone(timedelta(hours=-6))
CENT = Decimal("0.01")
PRODUCTS = ("promissory", "cede")
INVESTMENT_CHOICES = ("yes", "no", "unknown")
RATE_PHRASE = ("This is the reference annual gross rate for that amount and term as of the simulation date. "
               "Final conditions are confirmed upon formalizing the investment.")
ISR_DISCLAIMER = "The ISR is an estimated withholding; please validate its tax treatment with your accountant."


def load_data(path: Path = DATA_FILE) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def today_mexico_city() -> date:
    return datetime.now(MEXICO_CITY).date()


def _money(value: Decimal) -> str:
    return f"{value.quantize(CENT, rounding=ROUND_HALF_UP):,.2f}"


def _pct(value: Decimal) -> str:
    return f"{value.quantize(CENT, rounding=ROUND_HALF_UP):.2f}"


def _result(status: str, message: str, **extra) -> dict:
    return {"status": status, "message": message, **extra}


def _row_index(items: list, value) -> int:
    """Index of the last item whose lower bound is <= value (lower bound inclusive)."""
    index = -1
    for i, item in enumerate(items):
        if value >= Decimal(str(item["min"])):
            index = i
    return index


def _figures(amount: Decimal, rate: Decimal, days: int, tax: dict) -> dict:
    """Simulator V12 formulas, unrounded."""
    gross = amount * rate / 100 / 360 * days
    exempt = Decimal(tax["exempt_amount"])
    withholding = Decimal(tax["withholding_rate_percent"])
    isr = (amount - exempt) * withholding / 100 / 360 * days if amount > exempt else Decimal(0)
    net = gross - isr
    return {"gross": gross, "isr": isr, "net": net, "total": amount + net,
            "net_rate": net / amount / days * 360 * 100}


def _displayed(figures: dict, rate: Decimal) -> dict:
    return {
        "gross_rate_percent": _pct(rate),
        "gross_interest": _money(figures["gross"]),
        "estimated_isr": _money(figures["isr"]),
        "net_interest": _money(figures["net"]),
        "net_rate_percent": _pct(figures["net_rate"]),
        "total_at_maturity": _money(figures["total"]),
    }


def _consistent(figures: dict, amount: Decimal) -> bool:
    return (Decimal(0) <= figures["isr"] <= figures["gross"]
            and figures["net"] <= figures["gross"]
            and abs(figures["total"] - amount - figures["net"]) < CENT)


def _cede_periods(amount: Decimal, rate: Decimal, days: int, tax: dict) -> list:
    periods, start = [], 0
    while start < days:
        length = min(30, days - start)
        f = _figures(amount, rate, length, tax)
        periods.append({"days": length, "gross_interest": _money(f["gross"]),
                        "estimated_isr": _money(f["isr"]), "net_interest": _money(f["net"])})
        start += length
    return periods


def simulate(product: str, amount, days: int | None = None, maturity_date: date | None = None,
             opening_date: date | None = None, other_investments: str = "no",
             reinvestments: int | None = None, currency: str = "MXN", rate_only: bool = False,
             periods: bool = False, today: date | None = None, data: dict | None = None) -> dict:
    data = data or load_data()
    today = today or today_mexico_city()
    amount = Decimal(str(amount))
    opening = opening_date or today

    if product not in data["products"]:
        return _result("INVALID_INPUT", f"Unknown product. Use one of: {', '.join(PRODUCTS)}.")
    spec = data["products"][product]
    if currency.upper() != data["currency"]:
        return _result("UNSUPPORTED_CURRENCY", "The simulator only covers investments in MXN. "
                       "Use the fallback phrase: I do not have that updated data. Please consult a branch or Condusef.")
    if amount < Decimal(str(data["minimum_amount"])):
        return _result("INVALID_AMOUNT", f"The minimum investment is ${data['minimum_amount']:,}. Do not simulate.")
    if opening < today:
        return _result("INVALID_DATE", f"The opening date is in the past. Today is {today.isoformat()}: run again "
                       "without --opening-date (it defaults to today). Show no figures until a run returns OK.",
                       today=today.isoformat())
    if days is None and maturity_date is None:
        return _result("MISSING_TERM", "Ask for the term in days or the maturity date.")
    if maturity_date is not None:
        from_date = (maturity_date - opening).days
        if days is not None and days != from_date:
            return _result("INVALID_DATE", "The term in days and the maturity date do not match. Ask which one is right.")
        days = from_date
    if days < data["products"]["promissory"]["min_days"]:
        return _result("INVALID_TERM", "The minimum term is 7 days. Propose a term of 7 days or more.")
    if days < spec["min_days"]:
        return _result("CEDE_MIN_TERM", "CEDE +D60 starts at 60 days. Offer the Promissory Note for this term, "
                       "or a CEDE of 60 days or more.")

    term_index = _row_index(spec["term_ranges"], days)
    term_range = spec["term_ranges"][term_index]
    max_reinvest = term_range["max_reinvestments"]
    if reinvestments is not None:
        if max_reinvest is None:
            return _result("REINVEST_NOT_APPLICABLE", "Reinvestment does not apply to CEDE +D60 in the simulator.")
        if reinvestments > max_reinvest:
            return _result("REINVEST_LIMIT", f"The maximum for {term_range['label']} is {max_reinvest} "
                           "reinvestments. Inform the limit and review other terms.", max_reinvestments=max_reinvest)

    amount_index = _row_index(data["amount_brackets"], amount)
    rate = Decimal(spec["rates_percent"][amount_index][term_index])
    maturity = opening + timedelta(days=days)
    base = {
        "product": spec["name"],
        "amount": _money(amount),
        "currency": data["currency"],
        "days": days,
        "opening_date": opening.isoformat(),
        "maturity_date": maturity.isoformat(),
        "simulation_date": today.isoformat(),
        "rate_table": f"{data['source']} ({data['table_date']})",
        "amount_bracket": data["amount_brackets"][amount_index]["label"],
        "term_range": term_range["label"],
        "max_reinvestments": max_reinvest if max_reinvest is not None else "N/A",
    }

    campaign = data["campaign"]
    campaign_rate = Decimal(campaign["rate_percent"])
    campaign_applies = (campaign["active"] and days >= campaign["min_days"]
                        and product in campaign["products"] and campaign_rate > rate)

    if rate_only:
        out = _result("OK", "Quote this gross annual rate with the rate-quote phrase.",
                      gross_rate_percent=_pct(rate), **base)
        if campaign_applies:
            out["campaign"] = {"name": campaign["name"], "gross_rate_percent": _pct(campaign_rate)}
        return out

    tax = data["tax_years"].get(str(opening.year))
    if tax is None:
        return _result("ESCALATE_NO_TAX_DATA", f"No verified tax parameters for {opening.year}. Escalate to the "
                       "Commercial Executive; do not show figures.")

    figures = _figures(amount, rate, days, tax)
    if not _consistent(figures, amount):
        return _result("ESCALATE_INCONSISTENT", "The simulation failed its self-check. Escalate; do not show figures.")

    notes = []
    fiscal_crossing = maturity.year > opening.year
    if fiscal_crossing:
        notes.append(f"The term crosses into {maturity.year}: ISR was estimated with {opening.year} parameters, "
                     "may change, and will be confirmed by the Commercial Executive.")
    cumulative = other_investments != "no"
    if cumulative:
        notes.append("The client has (or may have) other active investments: ISR is estimated for this investment "
                     "alone and will be recalculated on the cumulative balance by the Commercial Executive.")

    disclaimer = (f"Simulation date {base['simulation_date']}, {base['rate_table']}. {RATE_PHRASE} "
                  f"{ISR_DISCLAIMER}")
    out = _result("OK", "Show these figures exactly and end the reply with `disclaimer` and `notes` word for word.",
                  **base,
                  **_displayed(figures, rate), tax_year=opening.year,
                  fiscal_year_crossing=fiscal_crossing, cumulative_isr_unverified=cumulative, notes=notes,
                  disclaimer=disclaimer, handover_id=str(uuid.uuid4()),
                  generated_at=datetime.now(MEXICO_CITY).isoformat(timespec="seconds"))
    if product == "cede":
        out["interest_paid"] = "every 30 days (last period proportional); principal at maturity"
        month = _figures(amount, rate, 30, tax)
        out["each_30_day_period"] = {"gross_interest": _money(month["gross"]),
                                     "estimated_isr": _money(month["isr"]), "net_interest": _money(month["net"])}
        if periods:
            out["periods"] = _cede_periods(amount, rate, days, tax)
    if campaign_applies:
        promo = _figures(amount, campaign_rate, days, tax)
        out["campaign"] = {"name": campaign["name"], **_displayed(promo, campaign_rate)}
    return out


# ── CLI ──────────────────────────────────────────────────────────────────


class _JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        print(json.dumps(_result("INVALID_INPUT", f"{message}. Pass digits and ISO dates only.")))
        sys.exit(2)


def _amount(text: str) -> Decimal:
    cleaned = text.replace(",", "")
    if not re.fullmatch(r"\d{1,12}(\.\d{1,2})?", cleaned):
        raise argparse.ArgumentTypeError("amount must be a number such as 150000 or 150000.50")
    return Decimal(cleaned)


def _days(text: str) -> int:
    if not re.fullmatch(r"\d{1,5}", text):
        raise argparse.ArgumentTypeError("days must be a whole number")
    return int(text)


def _iso_date(text: str) -> date:
    try:
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
            return date.fromisoformat(text)
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("dates must be YYYY-MM-DD")


def _currency(text: str) -> str:
    if not re.fullmatch(r"[A-Za-z]{3}", text):
        raise argparse.ArgumentTypeError("currency must be a 3-letter code such as MXN")
    return text.upper()


def main(argv: list | None = None) -> int:
    parser = _JsonArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--product", required=True, choices=PRODUCTS)
    parser.add_argument("--amount", required=True, type=_amount)
    term = parser.add_mutually_exclusive_group()
    term.add_argument("--days", type=_days)
    term.add_argument("--maturity-date", type=_iso_date)
    parser.add_argument("--opening-date", type=_iso_date, help="default: today (Mexico City)")
    parser.add_argument("--other-investments", choices=INVESTMENT_CHOICES, default="no")
    parser.add_argument("--reinvestments", type=_days)
    parser.add_argument("--currency", default="MXN", type=_currency)
    parser.add_argument("--rate-only", action="store_true", help="return only the gross annual rate")
    parser.add_argument("--periods", action="store_true", help="CEDE: include the 30-day period breakdown")
    parser.add_argument("--today", type=_iso_date, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    result = simulate(args.product, args.amount, days=args.days, maturity_date=args.maturity_date,
                      opening_date=args.opening_date, other_investments=args.other_investments,
                      reinvestments=args.reinvestments, currency=args.currency, rate_only=args.rate_only,
                      periods=args.periods, today=args.today)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
