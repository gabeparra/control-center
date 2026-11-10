#!/usr/bin/env python3
"""Regenerate demo.mp4, the test clip every demo library item plays.

Needs ffmpeg. The picture is ffmpeg's own SMPTE bars source with a running
seconds counter and a progress bar, the sound is a sine tone. Nothing is
downloaded and no third-party footage is used.

    python3 bridge/demo_media/make_demo.py
"""
import shutil
import subprocess
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / "demo.mp4"
SECONDS = 12

text = "box=1:boxcolor=black@0.6:fontcolor=white"
vf = (
    f"drawbox=x=0:y=ih/2-4:w=iw*t/{SECONDS}:h=8:color=white@0.9:t=fill,"
    f"drawtext=text='DEMO TEST PATTERN':x=(w-text_w)/2:y=h/2-90:fontsize=34:{text},"
    f"drawtext=text='%{{pts\\:gmtime\\:0\\:%S}} s':x=(w-text_w)/2:y=h/2+20:fontsize=72:{text}"
)


def main() -> int:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("ffmpeg not found", file=sys.stderr)
        return 1
    encoders = subprocess.run([ffmpeg, "-hide_banner", "-encoders"],
                              capture_output=True, text=True).stdout
    # Baseline H.264 plays everywhere; use whichever encoder this build has.
    video = (["-c:v", "libx264", "-profile:v", "baseline", "-crf", "30"]
             if " libx264 " in encoders else ["-c:v", "libopenh264", "-b:v", "120k"])
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
           "-f", "lavfi", "-i", f"smptebars=size=640x360:rate=24:duration={SECONDS}",
           "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=44100:duration={SECONDS}",
           "-vf", vf, *video, "-pix_fmt", "yuv420p", "-g", "24",
           "-c:a", "aac", "-b:a", "24k", "-ac", "1",
           "-movflags", "+faststart", "-shortest", str(OUT)]
    subprocess.run(cmd, check=True)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
