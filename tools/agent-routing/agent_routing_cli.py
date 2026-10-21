#!/usr/bin/env python3
"""Entry point usable without installation: python3 tools/agent-routing/agent_routing_cli.py <cmd>."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_routing.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
