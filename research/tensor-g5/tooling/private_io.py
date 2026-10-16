"""Local paths and whole-worker log capture for the InferDroid experiment."""

import os
from pathlib import Path
import resource
import signal
import sys
import tempfile
import time
import traceback


def workspace_root(script):
    directory = Path(script).resolve().parent
    for parent in (directory, *directory.parents):
        if (parent / ".private-workspace").is_file():
            return parent
        if (parent / "settings.gradle").is_file() and (parent / "app").is_dir():
            return (parent / ".local/tensor-g5").resolve()
    # A standalone scripts-only ZIP can use its extraction directory as the
    # private workspace. In a checkout, state always goes under .local/.
    return directory


def private_path(root, path):
    resolved = Path(path).expanduser().resolve()
    if not resolved.is_relative_to(root):
        raise ValueError("Inputs and outputs must stay inside the private workspace.")
    return resolved


def prepare_output(root, path):
    os.umask(0o077)
    output = private_path(root, path)
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("Use a new or empty output directory; existing results are preserved.")
    output.mkdir(parents=True, exist_ok=True)
    temporary = output / "tmp"
    temporary.mkdir()
    os.environ["TMPDIR"] = str(temporary)
    tempfile.tempdir = str(temporary)
    os.environ["HF_HOME"] = str(root / "cache/huggingface")
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    return output


def wait_worker(worker, output, min_free_bytes=0):
    """Stop only our worker group if the host is close to exhausting RAM."""
    stopped_at = None
    while True:
        done, status = os.waitpid(worker, os.WNOHANG)
        if done:
            return status
        try:
            if min_free_bytes and stopped_at is None:
                lines = Path("/proc/meminfo").read_text().splitlines()
                available = int(next(line for line in lines if line.startswith("MemAvailable:")).split()[1]) * 1024
                if available < min_free_bytes:
                    stopped_at = time.monotonic()
                    (output / "host-memory-guard.txt").write_text("Stopped this run to preserve host free RAM.\n")
                    try:
                        os.killpg(worker, signal.SIGTERM)
                    except ProcessLookupError:
                        os.kill(worker, signal.SIGTERM)
            elif stopped_at is not None and time.monotonic() - stopped_at > 3:
                os.killpg(worker, signal.SIGKILL)
            time.sleep(0.2)
        except KeyboardInterrupt:
            os.killpg(worker, signal.SIGINT)
            os.waitpid(worker, 0)
            raise
        except ProcessLookupError:
            pass


def run_private(output, work, min_free_bytes=0):
    sys.stdout.flush()
    sys.stderr.flush()
    with (output / "process.log").open("xb") as log:
        worker = os.fork()
        if worker == 0:
            os.setsid()
            os.dup2(log.fileno(), 1)
            os.dup2(log.fileno(), 2)
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
            try:
                return work()
            except BaseException:
                traceback.print_exc()
                return 1
        status = wait_worker(worker, output, min_free_bytes)
    code = os.waitstatus_to_exitcode(status)
    print("Completed; details kept in the private output directory." if code == 0 else
          "Run failed; inspect the private output directory locally.")
    return code if code in (0, 1, 2) else 1
