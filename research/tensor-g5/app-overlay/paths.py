"""Resolve authored sources separately from machine-local Android build state."""
from pathlib import Path
import sys

overlay = Path(__file__).resolve().parent
project = overlay.parents[2]
tooling = overlay.parent / "tooling"
sys.path.insert(0, str(tooling))

from private_io import workspace_root

private = workspace_root(__file__)
state = private / "android"
