#!/usr/bin/env python3
"""Run the Nebula Sim demo in an isolated copy of a local Minecraft world."""
import argparse
from fractions import Fraction
import json
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PLAYER_FIELDS = ("Dimension", "Pos", "Rotation", "playerGameType", "abilities")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=("1.21.1", "26.3"), default="1.21.1")
    parser.add_argument("--graphics", choices=("vulkan", "opengl"), help="RenderPearl backend for Minecraft 26.3")
    parser.add_argument("--world", type=Path, help="Copy an existing world into the isolated client")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--test", action="store_true", help="Run the hidden real-client smoke and exit")
    parser.add_argument("--record-seconds", type=int, default=0, help="Also capture up to 30 seconds in test mode")
    default_window_scale = 2 if platform.system() == "Darwin" else 1
    parser.add_argument("--width", type=int, default=1280, help="Physical capture width (default: 1280)")
    parser.add_argument("--height", type=int, default=720, help="Physical capture height (default: 720)")
    parser.add_argument("--fps", type=int, default=30, help="Capture frame rate (default: 30)")
    parser.add_argument("--window-scale", type=int, default=default_window_scale,
                        help=f"Headless framebuffer scale (default: {default_window_scale})")
    parser.add_argument("--timeout-seconds", type=int, default=600,
                        help="Real-client smoke timeout (default: 600)")
    parser.add_argument("--allow-slow-render", action="store_true",
                        help="Do not fail the smoke run when a high-resolution render exceeds the GPU budget")
    args = parser.parse_args()
    if not 0 <= args.record_seconds <= 30:
        parser.error("--record-seconds must be in 0..30")
    if args.width <= 0 or args.height <= 0:
        parser.error("--width and --height must be positive")
    if not 1 <= args.fps <= 120:
        parser.error("--fps must be in 1..120")
    if args.window_scale <= 0:
        parser.error("--window-scale must be positive")
    if args.timeout_seconds < 60:
        parser.error("--timeout-seconds must be at least 60")
    if args.record_seconds and not args.test:
        parser.error("--record-seconds requires --test")
    if args.record_seconds and (args.width % 2 or args.height % 2):
        parser.error("The video width and height must be even")
    if args.record_seconds and not all(shutil.which(tool) for tool in ("ffmpeg", "ffprobe")):
        parser.error("ffmpeg and ffprobe must be on PATH to record")
    modern = args.minecraft == "26.3"
    if args.graphics and not modern:
        parser.error("--graphics requires --minecraft 26.3")
    if args.world is None and not modern:
        args.world = ROOT / "modules/modelling/run/client/saves/New World"

    default_output = ROOT / "build" / ("nebula-sim-26.3" if modern else "nebula-sim")
    output = (args.output or default_output).resolve()
    client = output / "client"
    destination = client / "saves/nebula-sim"
    original_state = None
    modern_states = {}
    if args.world is not None:
        if not (args.world / "level.dat").is_file():
            parser.error("--world must point to an existing test world's folder")
        try:
            import nbtlib
        except ImportError:
            parser.error("Install nbtlib==2.0.4 to copy an existing world")
        source = args.world.resolve()
        if source == destination or source in destination.parents or destination in source.parents:
            parser.error("Source world and isolated output must be separate directories")
        if not destination.exists():
            shutil.copytree(source, destination, ignore=shutil.ignore_patterns("session.lock"))
        level = nbtlib.load(destination / "level.dat")
        original_player = level["Data"].get("Player")
        if original_player is not None:
            original_state = player_state(original_player)
        if modern:
            modern_states = {file.stem: player_state(nbtlib.load(file))
                             for file in (destination / "players/data").glob("*.dat")}
            if args.test:
                # The source folder is untouched and the isolated copy can be opened without an interactive warning.
                level["Data"]["confirmedExperimentalSettings"] = nbtlib.Byte(1)
        level["Data"]["LevelName"] = nbtlib.String("Nebula Sim (isolated copy)")
        level.save()
    logical_width = max(1, (args.width + args.window_scale - 1) // args.window_scale)
    logical_height = max(1, (args.height + args.window_scale - 1) // args.window_scale)
    options = {"version": "5023" if modern else "3955", "onboardAccessibility": "false", "fullscreen": "false",
               "enableVsync": "false", "overrideWidth": str(logical_width), "overrideHeight": str(logical_height),
               "renderDistance": "4", "simulationDistance": "5", "guiScale": "2",
               "maxFps": str(args.fps) if args.test else "260",
               "graphicsMode": "1", "soundCategory_master": "0.0", "pauseOnLostFocus": "false",
               "tutorialStep": "none", "showSubtitles": "false", "bobView": "false"}
    client.mkdir(parents=True, exist_ok=True)
    (client / "options.txt").write_text("".join(f"{k}:{v}\n" for k, v in options.items()))
    module = "multiversion" if modern else "modelling"
    command = ([str(ROOT / "scripts/run-headless-client.sh")] if args.test
               else [str(ROOT / "gradlew"), f":modules:{module}:runClient"])
    command += [f"-PkasugaMinecraft={args.minecraft}", "--console=plain", f"-PkasugaClientDirectory={client}"]
    if args.world is not None:
        command.append("-PkasugaNebulaWorld=nebula-sim" if modern else "-PkasugaQuickPlayWorld=nebula-sim")
    if not args.test or not modern:
        command.append("-PkasugaNebulaAuto=true")
    if modern:
        command += [f"-PkasugaNebulaWindowWidth={logical_width}", f"-PkasugaNebulaWindowHeight={logical_height}"]
        if args.graphics:
            property_name = "kasugaHeadlessGraphics" if args.test else "kasugaNebulaGraphics"
            command.append(f"-P{property_name}={args.graphics}")
    if args.test:
        command += ["-PkasugaTestNebula=true", f"-PkasugaNebulaOutput={output}",
                    f"-PkasugaNebulaRecordFrames={args.record_seconds * args.fps}",
                    f"-PkasugaNebulaCaptureFps={args.fps}", f"-PkasugaHeadlessFps={args.fps}",
                    f"-PkasugaNebulaEnforceGpuBudget={str(not args.allow_slow_render).lower()}",
                    f"-PkasugaNebulaTimeoutSeconds={args.timeout_seconds}"]
        if modern:
            command += [f"-PkasugaNebulaCreateWorld={str(args.world is None).lower()}",
                        f"-PkasugaNebulaWidth={args.width}", f"-PkasugaNebulaHeight={args.height}"]
    subprocess.run(command, cwd=ROOT, check=True)
    if args.test:
        report = json.loads((output / "report.json").read_text())
        if not report.get("passed"):
            raise SystemExit(f"Nebula smoke failed: {report.get('failure')}")
        frames = int(report.get("videoFrames", 0))
        if frames != args.record_seconds * args.fps:
            raise SystemExit(f"Recorded {frames} of {args.record_seconds * args.fps} requested frames")
        if frames:
            # The report spans the first recorded frame through the following close-frame boundary.
            elapsed = float(report["recordSimulatedSeconds"])
            expected = frames / args.fps
            if abs(elapsed - expected) > max(0.02, 1 / args.fps):
                raise SystemExit(f"Capture clock advanced incorrectly: {elapsed} seconds for {frames} frames at {args.fps} FPS")
            report["fixedStepCapture"] = True
            report["captureFps"] = args.fps
            report["captureWidth"] = int(report.get("nebula", {}).get("width", args.width))
            report["captureHeight"] = int(report.get("nebula", {}).get("height", args.height))
            report["requestedWidth"] = args.width
            report["requestedHeight"] = args.height
            report["windowScale"] = args.window_scale
            verify_video(output / "nebula-sim.mp4", args.width, args.height, args.fps, frames)
        if modern and report.get("playerUuid") in modern_states:
            player = report["playerUuid"]
            original_state = modern_states[player]
            saved_player = nbtlib.load(destination / "players/data" / f"{player}.dat")
        elif original_state is not None and modern:
            saved_player = nbtlib.load(destination / "players/data" / f"{report['playerUuid']}.dat")
        elif original_state is not None:
            saved_player = nbtlib.load(destination / "level.dat")["Data"].get("Player")
        if original_state is not None:
            if saved_player is None:
                raise SystemExit("The isolated world lost its saved player")
            saved_state = {key: saved_player[key].unpack() for key in original_state}
            if saved_state != original_state:
                raise SystemExit(f"Player state did not restore: {original_state} -> {saved_state}")
            report["savedPlayerRestored"] = True
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Nebula smoke passed; artifacts: {output}")


def player_state(player):
    return {key: player[key].unpack() for key in PLAYER_FIELDS if key in player}


def verify_video(file, width, height, fps, frames):
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
                            "-show_entries", "stream=width,height,r_frame_rate,nb_read_frames",
                            "-of", "json", str(file)], check=True, capture_output=True, text=True)
    stream = json.loads(probe.stdout)["streams"][0]
    if (stream["width"], stream["height"], Fraction(stream["r_frame_rate"]), int(stream["nb_read_frames"])) != (
            width, height, fps, frames):
        raise SystemExit(f"Unexpected encoded video stream {stream}")


if __name__ == "__main__":
    main()
