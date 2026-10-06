"""Fetch the unmodified 0.3.20 codec into the Actions-only integration source set."""
import hashlib
import os
from pathlib import Path
import urllib.request

REVISION = "37a969e571aa4f7c5cc25ab80c49cca4e8a51cc7"
SOURCES = {
    "SyncmaticaPacket.java": "5e282026b9f0f3e7b45b57e4168369bab664854a8e1596eb6d799518cd65b423",
    "PacketType.java": "2bae217bc6fb869d6dca17ef33fd420942dfa32d41b9dc73f4a1c1015d9954ff",
}


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Prepare the client codec fixture only in GitHub Actions")
    root = Path(__file__).resolve().parents[1] / "build/generated/syncmatica-codec/ch/endte/syncmatica"
    network = root / "network"
    network.mkdir(parents=True, exist_ok=True)
    for name, expected in SOURCES.items():
        url = f"https://raw.githubusercontent.com/sakura-ryoko/syncmatica/{REVISION}/src/main/java/ch/endte/syncmatica/network/{name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        assert hashlib.sha256(data).hexdigest() == expected, f"Unexpected source for {name}"
        (network / name).write_bytes(data)
    # The codec's only mod-level dependency is NETWORK_ID. Avoid starting Fabric or
    # the mod's GUI/lifecycle on Paper; the two upstream codec sources above are unchanged.
    (root / "Syncmatica.java").write_text(
        'package ch.endte.syncmatica;\n'
        'public final class Syncmatica {\n'
        '    public static final net.minecraft.resources.Identifier NETWORK_ID =\n'
        '        net.minecraft.resources.Identifier.parse("syncmatica:main");\n'
        '}\n', encoding="utf-8")
    print(f"Pinned Syncmatica 0.3.20 codec prepared: {REVISION}")


if __name__ == "__main__":
    main()
