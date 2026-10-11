"""Records Kraken's public BTC/USD order book and trades. Stage A: connect, subscribe, print."""

import asyncio
import json
from pathlib import Path
import time

import websockets

URL = "wss://ws.kraken.com/v2"
SYMBOL = "BTC/USD"
BOOK_DEPTH = 25
DATA_DIR = Path("data")
OUT_FILE = DATA_DIR / "kraken_btcusd.tsv"


async def main() -> None:

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    count = 0

    with open(OUT_FILE, "a", encoding="utf-8") as f:
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
                    "symbol": [SYMBOL]
                }
            }

            await ws.send(json.dumps(trade_request))

            async for raw in ws:
                recv_ns = time.time_ns()
                f.write(f"{recv_ns}\t{raw}\n")
                count = count + 1
                if count % 500 == 0:
                    print(count)

if __name__ == "__main__":
    asyncio.run(main())