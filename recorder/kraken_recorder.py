"""Records Kraken's public BTC/USD order book and trades.

Every raw message is appended, with a receive timestamp, to one
gzip-compressed file per UTC hour: data/kraken_btcusd_YYYY-MM-DDTHH.tsv.gz

Stage D (in progress): D1 done, one connection's lifetime lives in record_once();
main() owns the writer. Reconnecting comes in D2.
"""

import asyncio
import gzip
import json
import time
from datetime import datetime, timezone
from pathlib import Path

import websockets

URL = "wss://ws.kraken.com/v2"
SYMBOL = "BTC/USD"
BOOK_DEPTH = 25
DATA_DIR = Path("data")
FILE_PREFIX = "kraken_btcusd"
RECONNECT_MIN = 1
RECONNECT_MAX = 60
HEALTHY_AFTER = 60
SILENCE_TIMEOUT = 30



class HourlyWriter:
    """Writes lines into one gzip file per UTC hour: <dir>/<prefix>_YYYY-MM-DDTHH.tsv.gz"""

    def __init__(self, directory: Path, prefix: str) -> None:
        self.directory = directory
        self.prefix = prefix
        self.current_key: str | None = None   # which hour's file is open, e.g. "2026-10-11T18"
        self.file = None                      # the open gzip file, or None

    @staticmethod
    def hour_key(recv_ns: int) -> str:
        """The UTC hour a timestamp belongs to, as text like '2026-10-11T18'."""
        seconds = recv_ns // 1_000_000_000
        return datetime.fromtimestamp(seconds, tz=timezone.utc).strftime("%Y-%m-%dT%H")

    def path_for(self, key: str) -> Path:
        return self.directory / f"{self.prefix}_{key}.tsv.gz"

    def write(self, recv_ns: int, raw: str) -> None:
        """Write one line into the file for recv_ns's hour, switching files if the hour changed."""
        key = self.hour_key(recv_ns)
        if key != self.current_key:                     # new hour (or the very first message)
            self.close()
            self.directory.mkdir(parents=True, exist_ok=True)
            self.file = gzip.open(self.path_for(key), "at", encoding="utf-8")
            self.current_key = key
        self.file.write(f"{recv_ns}\t{raw}\n")

    def close(self) -> None:
        """Close the current file, if any. Safe to call more than once."""
        if self.file is not None:
            self.file.close()
            self.file = None
            self.current_key = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()


async def record_once(writer: HourlyWriter) -> None:
    """One connection's lifetime: connect, subscribe, record until the connection ends."""
    count = 0
    async with websockets.connect(URL) as ws:
        book_request = {
            "method": "subscribe",
            "params": {
                "channel": "book",
                "symbol": [SYMBOL],
                "depth": BOOK_DEPTH,
            },
        }
        await ws.send(json.dumps(book_request))

        trade_request = {
            "method": "subscribe",
            "params": {
                "channel": "trade",
                "symbol": [SYMBOL],
            },
        }
        await ws.send(json.dumps(trade_request))

        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=SILENCE_TIMEOUT)
            recv_ns = time.time_ns()          # first: as close to arrival as possible
            writer.write(recv_ns, raw)
            count += 1
            if count % 500 == 0:
                print(f"recorded {count} messages")


async def main() -> None:
    """Supervisor: keep recording forever, reconnecting with exponential backoff."""
    backoff = RECONNECT_MIN
    with HourlyWriter(DATA_DIR, FILE_PREFIX) as writer:     # file stays open across reconnects
        while True:
            started = time.monotonic()
            try:
                await record_once(writer)
                print("connection closed by the server")
            except (websockets.ConnectionClosed, websockets.InvalidHandshake, OSError) as e:
                print(f"connection lost: {e!r}")

            lasted = time.monotonic() - started
            if lasted >= HEALTHY_AFTER:                     # it was a normal blip: retry fast
                backoff = RECONNECT_MIN

            print(f"connection lasted {lasted:.1f} s, waiting {backoff} s before reconnecting")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, RECONNECT_MAX)       # 1, 2, 4, ... 32, 60, 60, ...

if __name__ == "__main__":
    asyncio.run(main())