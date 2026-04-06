"""
Analyze SoccerNet labels to find the densest 10-minute windows.

Scans all Labels-v2.json files, scores events by importance, and finds
non-overlapping 10-minute windows with the highest concentration of
interesting events.

Usage:
  python scripts/analyze_events.py [--top N] [--window SECONDS]

Output:
  samples/hls/selections.json — the selected windows with ground truth events
"""

import argparse
import json
import subprocess
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SOCCERNET_DIR = PROJECT_ROOT / "soccernet"
HLS_DIR = PROJECT_ROOT / "samples" / "hls"

# Event weights — higher = more interesting
WEIGHTS = {
    "Goal": 10, "Penalty": 9, "Red card": 8,
    "Yellow card": 5, "Shots on target": 4, "Shots off target": 2,
    "Direct free-kick": 2, "Indirect free-kick": 1, "Corner": 1,
    "Foul": 1, "Offside": 1,
}


def get_video_duration(path):
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0 or not result.stdout.strip():
        print(f"  WARNING: ffprobe failed for {path}, skipping")
        return None
    return float(result.stdout.strip())


def load_events():
    """Load all weighted events grouped by (game, half)."""
    halves = {}
    for label_file in sorted(SOCCERNET_DIR.rglob("Labels-v2.json")):
        game = str(label_file.parent.relative_to(SOCCERNET_DIR))
        data = json.loads(label_file.read_text())

        for e in data["annotations"]:
            label = e["label"]
            if label not in WEIGHTS:
                continue
            half = int(e["gameTime"].split(" - ")[0])
            pos_sec = int(e["position"]) / 1000
            key = (game, half)
            halves.setdefault(key, []).append({
                "label": label,
                "position_sec": pos_sec,
                "game_time": e["gameTime"],
                "team": e["team"],
                "weight": WEIGHTS[label],
            })
    return halves


def find_best_windows(halves, window_sec, top_n):
    """Sliding window search for densest non-overlapping windows."""
    candidates = []

    for (game, half), events in halves.items():
        mkv = SOCCERNET_DIR / game / f"{half}_720p.mkv"
        if not mkv.exists():
            continue
        dur = get_video_duration(mkv)
        if dur is None:
            continue

        # Slide in 30-second steps, skip first minute
        for start in range(60, int(dur) - window_sec, 30):
            end = start + window_sec
            window_events = [e for e in events if start <= e["position_sec"] < end]
            score = sum(e["weight"] for e in window_events)
            if score > 0:
                candidates.append({
                    "game": game,
                    "half": half,
                    "start": start,
                    "end": end,
                    "score": score,
                    "events": window_events,
                })

    # Sort by score descending
    candidates.sort(key=lambda x: -x["score"])

    # Pick top non-overlapping
    selected = []
    for c in candidates:
        if len(selected) >= top_n:
            break
        overlap = any(
            s["game"] == c["game"] and s["half"] == c["half"]
            and not (c["end"] <= s["start"] or c["start"] >= s["end"])
            for s in selected
        )
        if not overlap:
            selected.append(c)

    return selected


def main():
    parser = argparse.ArgumentParser(description="Find densest event windows in SoccerNet games")
    parser.add_argument("--top", type=int, default=5, help="Number of windows to select (default: 5)")
    parser.add_argument("--window", type=int, default=600, help="Window size in seconds (default: 600)")
    args = parser.parse_args()

    halves = load_events()
    if not halves:
        print("No labels found in soccernet/. Download games first.")
        return

    selected = find_best_windows(halves, args.window, args.top)

    # Print summary
    print(f"=== Top {len(selected)} densest {args.window // 60}-minute windows ===\n")
    for i, s in enumerate(selected):
        game_short = s["game"].split("/")[-1][:50]
        print(f"{i+1}. Score={s['score']:3d}  H{s['half']} "
              f"{s['start']//60}:{s['start']%60:02d}–{s['end']//60}:{s['end']%60:02d}  "
              f"{game_short}")
        for e in sorted(s["events"], key=lambda x: x["position_sec"]):
            offset = e["position_sec"] - s["start"]
            print(f"     {offset:5.0f}s  {e['label']:20s}  {e['game_time']}  {e['team']}")
        print()

    # Write selections.json
    HLS_DIR.mkdir(parents=True, exist_ok=True)
    output = []
    for s in selected:
        output.append({
            "game": s["game"],
            "half": s["half"],
            "start": s["start"],
            "end": s["end"],
            "score": s["score"],
            "events": [
                {
                    "label": e["label"],
                    "game_time": e["game_time"],
                    "position_sec": e["position_sec"],
                    "offset_sec": round(e["position_sec"] - s["start"], 1),
                    "team": e["team"],
                    "weight": e["weight"],
                }
                for e in sorted(s["events"], key=lambda x: x["position_sec"])
            ],
        })

    selections_path = HLS_DIR / "selections.json"
    selections_path.write_text(json.dumps(output, indent=2))
    print(f"Selections written to: {selections_path}")


if __name__ == "__main__":
    main()
