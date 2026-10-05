"""Builds are separate; this runner boots and tests Paper only in GitHub Actions."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

from network_probe import verify_network
from feature_probe import verify_features


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Run this integration check in GitHub Actions")
    project = Path(__file__).resolve().parents[1]
    props = dict(line.split("=", 1) for line in (project / "gradle.properties").read_text().splitlines() if "=" in line)
    folder = project / "build/ci-server"
    plugins = folder / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    jars = [p for p in (project / "build/libs").glob("*.jar") if not p.name.endswith("-sources.jar")]
    assert len(jars) == 2, f"Expected plugin plus test fixture, got {jars}"
    for jar in jars:
        shutil.copy2(jar, plugins / jar.name)
    checksum = props["paper_server_sha256"]
    url = f"https://fill-data.papermc.io/v1/objects/{checksum}/paper-{props['mc_version']}-{props['paper_build']}.jar"
    request = urllib.request.Request(url, headers={"User-Agent": "servux-ci (https://github.com/MhuaerC/servux)"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                data = response.read()
            break
        except (OSError, TimeoutError):
            if attempt == 3:
                raise
            time.sleep(5 * (attempt + 1))
    assert hashlib.sha256(data).hexdigest() == checksum, "Paper checksum mismatch"
    (folder / "paper.jar").write_bytes(data)
    (folder / "eula.txt").write_text("eula=true\n")
    (folder / "server.properties").write_text(
        "online-mode=false\nenforce-secure-profile=false\nserver-ip=127.0.0.1\n"
        "server-port=25565\nwhite-list=false\nlevel-type=minecraft:flat\nview-distance=2\nsimulation-distance=2\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
        "max-players=2\nspawn-protection=0\n"
    )
    for resumed in (False, True):
        run_server(folder, resumed)


def run_server(folder, resumed):
    # A second JVM proves persistence rather than just retention in an in-memory map.
    (folder / "network-fixture.json").unlink(missing_ok=True)
    log_path = folder / ("server-restart.log" if resumed else "server.log")
    with log_path.open("w") as log:
        process = subprocess.Popen(["java", "-Xms512M", "-Xmx2G", "-jar", "paper.jar", "--nogui"],
                                   cwd=folder, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                output = log_path.read_text(errors="replace")
                if "SERVUX_FIXTURE_FAILED" in output or process.poll() is not None:
                    raise RuntimeError("Paper integration fixture failed to start")
                if "SERVUX_FIXTURE_READY" in output:
                    break
                time.sleep(1)
            else:
                raise TimeoutError("Paper startup timed out")
            verify_network(folder / "network-fixture.json")
            verify_features(folder / "network-fixture.json", resumed=resumed)
            process.stdin.write("servux reload\nservux list\nstop\n")
            process.stdin.flush()
            assert process.wait(timeout=60) == 0, "Unclean server shutdown"
            output = log_path.read_text(errors="replace")
            for marker in ("Error occurred while enabling", "Error occurred while disabling", "Could not pass event", "generated an exception", "LEAK:"):
                assert marker not in output, marker
            print("SERVUX_ACTIONS_OK: MiniHUD, Easy Place, Syncmatica, persistence and clean shutdown")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            print(log_path.read_text(errors="replace"))


if __name__ == "__main__":
    main()
