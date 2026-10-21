"""Per-provider usage/cost ledger (JSONL) with configurable daily token / USD budgets."""
import datetime
import json
from pathlib import Path


class BudgetExceeded(Exception):
    code = "BUDGET_EXCEEDED"


class Ledger:
    def __init__(self, path, daily_budgets=None, today=None):
        self.path = Path(path)
        self.budgets = daily_budgets or {}
        self._today = today

    def today(self):
        return (self._today or datetime.date.today()).isoformat()

    def totals(self, provider):
        tokens, usd = 0, 0.0
        if self.path.exists():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if row.get("provider") == provider and row.get("date") == self.today():
                    tokens += int(row.get("input_tokens") or 0) + int(row.get("output_tokens") or 0)
                    usd += float(row.get("cost_usd") or 0.0)
        return tokens, usd

    def check(self, provider, estimated_tokens):
        lim = self.budgets.get(provider) or {}
        if lim.get("tokens") is None and lim.get("usd") is None:
            return
        tokens, usd = self.totals(provider)
        if lim.get("tokens") is not None and tokens + estimated_tokens > lim["tokens"]:
            raise BudgetExceeded("BUDGET_EXCEEDED: %s daily token budget %s (used %s, est %s)" % (
                provider, lim["tokens"], tokens, estimated_tokens))
        if lim.get("usd") is not None and usd >= lim["usd"]:
            raise BudgetExceeded("BUDGET_EXCEEDED: %s daily USD budget %s (used %.4f)" % (provider, lim["usd"], usd))

    def record(self, provider, model, input_tokens, output_tokens, cost_usd, ok=True, error=None):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        row = {"ts": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
               "date": self.today(), "provider": provider, "model": model, "input_tokens": input_tokens,
               "output_tokens": output_tokens, "cost_usd": cost_usd, "ok": ok, "error": error}
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row) + "\n")
