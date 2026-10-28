#!/usr/bin/env python3
"""Make the BUSY S8 look like a BUSY Bar plugged in over USB, for the BUSY desktop app.

A real bar shows up as a USB-Ethernet adapter and answers at 10.0.4.20; the desktop app only
looks there. The S8 (Android 9) cannot be a USB-Ethernet device for macOS, so this relays
10.0.4.20:80 on this computer to the phone's API over the network instead.

First give this computer the address (once per boot):

    macOS:  sudo ifconfig lo0 alias 10.0.4.20/32
    Linux:  sudo ip addr add 10.0.4.20/32 dev lo

Then run:

    python3 tools/usb-bridge.py [phone address, default busybar.local:8080]

Plain TCP relay, so HTTP and the status WebSocket both pass through. Stop with Ctrl-C; remove
the address with `sudo ifconfig lo0 -alias 10.0.4.20` (Linux: `ip addr del`).
"""
import asyncio
import socket
import sys

ADDRESS = "10.0.4.20"
PORT = 80


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


def address_present() -> bool:
    """Whether 10.0.4.20 is on this computer: only then can a socket bind to it."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind((ADDRESS, 0))
        return True
    except OSError:
        return False
    finally:
        s.close()


async def main(target_host: str, target_port: int) -> None:
    # Resolve once (prefer IPv4) so each connection does not wait on mDNS.
    infos = socket.getaddrinfo(target_host, target_port, socket.AF_INET, socket.SOCK_STREAM)
    target = infos[0][4][0]

    async def handle(client_r: asyncio.StreamReader, client_w: asyncio.StreamWriter) -> None:
        # Listening on every address (see below), so serve only connections made to 10.0.4.20.
        if client_w.get_extra_info("sockname")[0] != ADDRESS:
            client_w.close()
            return
        try:
            phone_r, phone_w = await asyncio.wait_for(asyncio.open_connection(target, target_port), 10)
        except (OSError, asyncio.TimeoutError) as e:
            print(f"cannot reach the phone at {target}:{target_port}: {e}", file=sys.stderr)
            client_w.close()
            return
        await asyncio.gather(pipe(client_r, phone_w), pipe(phone_r, client_w))

    # macOS lets ordinary users listen on port 80 only on the wildcard address, not on one
    # address, so listen everywhere and let handle() turn away anything not sent to 10.0.4.20.
    if not address_present():
        sys.exit(f"{ADDRESS} is not on this computer yet; add it first (see --help)")
    try:
        server = await asyncio.start_server(handle, "0.0.0.0", PORT)
    except OSError as e:
        sys.exit(f"cannot listen on port {PORT} ({e})")
    print(f"{ADDRESS} -> {target}:{target_port} ({target_host}); Ctrl-C to stop")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    if any(a in ("-h", "--help") for a in sys.argv[1:]):
        print(__doc__)
        sys.exit(0)
    host, _, port = (sys.argv[1] if len(sys.argv) > 1 else "busybar.local:8080").partition(":")
    try:
        asyncio.run(main(host, int(port or 8080)))
    except KeyboardInterrupt:
        pass
