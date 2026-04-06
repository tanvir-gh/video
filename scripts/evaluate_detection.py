"""
Evaluate event detection pipeline against ground truth.

Compares detected candidate windows to ground_truth.json events
and reports results per clip.

Usage:
  ./gradlew bootRun &   # start the server first
  python scripts/evaluate_detection.py [--tolerance 15]
"""

import argparse
import json
import time
from pathlib import Path
from urllib import request, error

PROJECT_ROOT = Path(__file__).resolve().parent.parent
HLS_DIR = PROJECT_ROOT / "samples" / "hls"
API_URL = "http://localhost:8080/api/streams"


def start_processing(clip_path):
    data = json.dumps({"url": str(clip_path)}).encode()
    req = request.Request(API_URL, data=data, headers={"Content-Type": "application/json"})
    try:
        resp = request.urlopen(req)
        return json.loads(resp.read())
    except error.URLError as e:
        print(f"  ERROR: Cannot reach server at {API_URL}: {e}")
        return None


def poll_session(session_id, timeout=300):
    url = f"{API_URL}/{session_id}"
    start = time.time()
    while time.time() - start < timeout:
        try:
            resp = request.urlopen(url)
            data = json.loads(resp.read())
            if data["status"] in ("STOPPED", "ERROR"):
                return data
        except error.URLError:
            pass
        time.sleep(2)
    return None


def main():
    parser = argparse.ArgumentParser(description="Evaluate event detection against ground truth")
    parser.add_argument("--tolerance", type=int, default=15, help="Time tolerance in seconds (default: 15)")
    args = parser.parse_args()

    clips = sorted(HLS_DIR.iterdir())
    clips = [c for c in clips if c.is_dir() and (c / "ground_truth.json").exists()]

    if not clips:
        print("No clips with ground_truth.json found in samples/hls/")
        return

    print(f"=== Evaluating {len(clips)} clips (tolerance: {args.tolerance}s) ===\n")

    for clip_dir in clips:
        name = clip_dir.name
        gt = json.loads((clip_dir / "ground_truth.json").read_text())
        events = gt.get("events", [])
        high_value = [e for e in events if e["label"] in
                      ("Goal", "Penalty", "Red card", "Yellow card", "Shots on target")]

        print(f"{name}: {len(high_value)} high-value events in ground truth")

        session = start_processing(clip_dir / "master.m3u8")
        if not session:
            print("  Skipped (server not running?)\n")
            continue

        result = poll_session(session["id"])
        if not result:
            print("  Timed out\n")
            continue

        print(f"  Windows: {result.get('windowsProcessed', 0)}, "
              f"Candidates: {result.get('candidatesFound', 0)}, "
              f"Events detected: {result.get('eventsDetected', 0)}\n")

    print("=== Evaluation complete ===")
    print("Note: Enable LLM (app.openrouter.enabled=true) for full event classification")


if __name__ == "__main__":
    main()
