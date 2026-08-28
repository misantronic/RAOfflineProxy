from __future__ import annotations

import argparse
import threading
from pathlib import Path

from .server import build_servers
from .state import RaState


def main() -> int:
    parser = argparse.ArgumentParser(prog="fake_ra")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8181)
    parser.add_argument("--ctl-port", type=int, default=8182)
    parser.add_argument("--state", default=None)
    parser.add_argument("--no-user-agent-check", action="store_true")
    args = parser.parse_args()

    state = RaState(Path(args.state) if args.state else None)
    state.enforce_user_agent = not args.no_user_agent_check

    ra_server, ctl_server = build_servers(state, args.host, args.port, args.ctl_port)
    ctl_thread = threading.Thread(target=ctl_server.serve_forever, daemon=True)
    ctl_thread.start()

    print("fake RA on http://%s:%d" % (args.host, args.port), flush=True)
    print("control on http://%s:%d" % (args.host, args.ctl_port), flush=True)
    try:
        ra_server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        ra_server.shutdown()
        ctl_server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
