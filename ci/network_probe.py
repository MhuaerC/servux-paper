"""Independent TCP checks for the three channels in MattLavalleeMA's Paper port."""
import gzip
import io
import json
import socket
import struct
import time
import uuid
import zlib

CHANNELS = {"servux:structures": (3, 3), "servux:hud_metadata": (3, 2), "servux:entity_data": (2, 2)}

def varint(value):
    out = bytearray()
    while True:
        part = value & 127
        value >>= 7
        out.append(part | (128 if value else 0))
        if not value:
            return bytes(out)


def read_varint(read):
    value = 0
    for shift in range(0, 35, 7):
        part = read(1)
        if len(part) != 1:
            raise EOFError("Truncated VarInt")
        value |= (part[0] & 127) << shift
        if not part[0] & 128:
            return value
    raise ValueError("Oversized VarInt")


def utf(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


def read_utf(stream):
    return stream.read(read_varint(stream.read)).decode("utf-8")


def nbt_string(stream):
    length = struct.unpack(">H", stream.read(2))[0]
    return stream.read(length).decode("utf-8")


def nbt_value(stream, kind):
    scalar = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
    if kind in scalar:
        fmt = scalar[kind]
        return struct.unpack(fmt, stream.read(struct.calcsize(fmt)))[0]
    if kind == 8:
        return nbt_string(stream)
    if kind == 10:
        result = {}
        while True:
            child = stream.read(1)[0]
            if not child:
                return result
            name = nbt_string(stream)
            result[name] = nbt_value(stream, child)
    if kind == 9:
        child = stream.read(1)[0]
        count = struct.unpack(">i", stream.read(4))[0]
        return [nbt_value(stream, child) for _ in range(count)]
    if kind in (7, 11, 12):
        count = struct.unpack(">i", stream.read(4))[0]
        child = {7: 1, 11: 3, 12: 4}[kind]
        return [nbt_value(stream, child) for _ in range(count)]
    raise ValueError(f"Unknown NBT type {kind}")


def read_nbt(stream, named=False):
    kind = stream.read(1)[0]
    if not kind:
        return None
    if named:
        nbt_string(stream)
    return nbt_value(stream, kind)


def compressed_nbt(stream):
    length = struct.unpack(">i", stream.read(4))[0]
    return read_nbt(io.BytesIO(gzip.decompress(stream.read(length))), named=True)


def metadata_request(version):
    # Unnamed network NBT compound, containing an int named "version".
    return b"\x0a\x03\x00\x07version" + struct.pack(">i", version) + b"\x00"


class Probe:
    def __init__(self, fixture, port, username="ServuxTCPTest"):
        self.fixture = fixture
        self.username = username
        self.checks = set()
        self.fragments = {}
        self.entity_id = None
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=15)
        self.socket.settimeout(15)
        self.compression = None
        self.phase = "login"
        self.advertised = set()
        self.metadata = {}

    def read_exact(self, length):
        data = bytearray()
        while len(data) < length:
            part = self.socket.recv(length - len(data))
            if not part:
                raise EOFError("Server closed the network connection")
            data.extend(part)
        return bytes(data)

    def send_raw(self, packet_id, body=b""):
        payload = varint(packet_id) + body
        if self.compression is not None:
            payload = (varint(len(payload)) + zlib.compress(payload) if len(payload) >= self.compression
                       else b"\x00" + payload)
        self.socket.sendall(varint(len(payload)) + payload)

    def send(self, name, body=b""):
        self.send_raw(self.fixture[self.phase + "_c2s"][name], body)

    def read_packet(self):
        length = read_varint(self.read_exact)
        stream = io.BytesIO(self.read_exact(length))
        if self.compression is not None:
            size = read_varint(stream.read)
            if size:
                data = zlib.decompress(stream.read())
                assert len(data) == size, "Compression size mismatch"
                stream = io.BytesIO(data)
        packet_id = read_varint(stream.read)
        types = self.fixture[self.phase + "_s2c"]
        name = next((name for name, value in types.items() if value == packet_id), f"unknown_{packet_id}")
        return name, stream

    def custom(self, channel, body):
        self.send("custom_payload", utf(channel) + body)

    def run(self):
        self.send_raw(0, varint(self.fixture["protocol"]) + utf("localhost") + struct.pack(">H", 25565) + varint(2))
        self.send("hello", utf(self.username) + uuid.uuid3(uuid.NAMESPACE_DNS, self.username).bytes)
        deadline = time.monotonic() + 45
        requests_sent = False
        while time.monotonic() < deadline:
            name, body = self.read_packet()
            if name in ("disconnect", "login_disconnect"):
                raise AssertionError(f"Disconnected in {self.phase}: {body.read()!r}")
            if self.phase == "login":
                if name == "login_compression":
                    self.compression = read_varint(body.read)
                elif name == "login_finished":
                    self.send("login_acknowledged")
                    self.phase = "configuration"
                    self.send("client_information", utf("en_us") + b"\x02\x00\x01\x7f\x01\x00\x00\x00")
                elif name == "custom_query":
                    self.send("custom_query_answer", varint(read_varint(body.read)) + b"\x00")
                elif name == "hello":
                    raise AssertionError("CI server must use offline mode")
                continue
            if name == "keep_alive":
                self.send("keep_alive", body.read())
            elif name == "ping":
                self.send("pong", body.read())
            elif name == "select_known_packs":
                self.send("select_known_packs", b"\x00")
            elif name == "code_of_conduct":
                self.send("accept_code_of_conduct")
            elif name == "finish_configuration":
                self.send("finish_configuration")
                self.phase = "play"
            elif name == "player_position":
                teleport = read_varint(body.read)
                position = body.read(24)
                body.read(24)  # delta movement
                rotation = body.read(8)
                self.send("accept_teleportation", varint(teleport) + position + rotation)
                self.send("player_loaded")
            elif name == "login" and self.phase == "play":
                self.entity_id = struct.unpack(">i", body.read(4))[0]
            elif name == "custom_payload":
                channel = read_utf(body)
                self.receive_custom_payload(channel, body)
            if self.phase == "play" and set(CHANNELS) <= self.advertised and self.entity_id is not None and not requests_sent:
                # Like ClientPlayNetworking.canSend: wait for the server's channel advertisement.
                # Deliberately send NO client minecraft:register: test the actual outbound transport too.
                for channel, (version, request_type) in CHANNELS.items():
                    self.custom(channel, b"")
                    self.custom(channel, varint(request_type) + metadata_request(version))
                self.request_data()
                requests_sent = True
            expected = {"chest", "entity", "permissions", "spawn", "weather", "loggers", "recipes", "fragments"}
            if len(self.metadata) == 3 and expected <= self.checks:
                print("SERVUX_TCP_OK:", sorted(self.metadata), sorted(self.checks), flush=True)
                return
        raise TimeoutError(f"Incomplete: advertised={self.advertised}, metadata={self.metadata}, checks={self.checks}")

    def receive_custom_payload(self, channel, body):
        if channel == "minecraft:register":
            self.advertised.update(body.read().decode("utf-8").strip("\0").split("\0"))
        elif channel in CHANNELS or channel == "servux:ci_fragment":
            self.receive_servux(channel, body)

    def request_data(self):
        self.custom("servux:hud_metadata", varint(4))
        self.custom("servux:hud_metadata", varint(6))
        nbt = b"\x0a\x00\x00\x01\x00\x03tps\x01\x01\x00\x08mob_caps\x01\x00"
        data = gzip.compress(nbt)
        self.custom("servux:hud_metadata", varint(8) + struct.pack(">i", len(data)) + data)
        self.custom("servux:entity_data", varint(3) + struct.pack(">Q", 100))
        self.custom("servux:entity_data", varint(4) + varint(self.fixture["entity_id"]))
        self.custom("servux:entity_data", varint(4) + varint(self.entity_id))

    def fragment(self, channel, body):
        if channel not in self.fragments:
            self.fragments[channel] = (read_varint(body.read), bytearray())
        total, data = self.fragments[channel]
        data.extend(body.read())
        assert len(data) <= total
        if len(data) != total:
            return
        del self.fragments[channel]
        if channel == "servux:ci_fragment":
            assert total == 2 * (1048576 - 5) + 32
            assert all(value == i % 251 for i, value in enumerate(data)), "Fragment reassembly mismatch"
            self.checks.add("fragments")
        elif channel == "servux:hud_metadata":
            recipes = compressed_nbt(io.BytesIO(data))["RecipeManager"]
            assert len(recipes) > 100, len(recipes)
            assert any(recipe["id_value"] == "minecraft:crafting_table" for recipe in recipes)
            self.checks.add("recipes")

    def receive_servux(self, channel, body):
        kind = read_varint(body.read)
        if kind == 1 and channel in CHANNELS:
            metadata = read_nbt(body)
            assert metadata["version"] == CHANNELS[channel][0], metadata
            assert metadata["servux"].startswith("servux-fabric-26.3-"), metadata
            if channel == "servux:hud_metadata":
                assert "worldSeed" not in metadata, "Non-operator received world seed"
            self.metadata[channel] = metadata
        elif channel == "servux:entity_data" and kind == 5:
            assert struct.unpack(">Q", body.read(8))[0] == 100
            nbt = compressed_nbt(body)
            assert nbt["id"] == "minecraft:chest", nbt
            item = nbt["Items"][0]
            assert item["id"] == "minecraft:diamond" and item["count"] == 3, item
            self.checks.add("chest")
        elif channel == "servux:entity_data" and kind == 6:
            entity_id = read_varint(body.read)
            nbt = compressed_nbt(body)
            if entity_id == self.entity_id:
                assert nbt["id"] == "minecraft:player"
                assert not nbt.get("Inventory") and not nbt.get("EnderItems"), "Private inventory leaked"
                self.checks.add("permissions")
            else:
                assert entity_id == self.fixture["entity_id"] and nbt["id"] == "minecraft:pig", nbt
                self.checks.add("entity")
        elif channel == "servux:hud_metadata" and kind in (3, 5, 7):
            nbt = compressed_nbt(body)
            if kind == 3:
                assert (nbt["spawnPosX"], nbt["spawnPosY"], nbt["spawnPosZ"]) == (0, 101, 2), nbt
                assert "worldSeed" not in nbt
                self.checks.add("spawn")
            elif kind == 5:
                assert "isRaining" in nbt and "isThundering" in nbt, nbt
                self.checks.add("weather")
            else:
                assert "tps" in nbt and "mob_caps" in nbt, nbt
                self.checks.add("loggers")
        elif (channel == "servux:hud_metadata" and kind == 11) or (channel == "servux:ci_fragment" and kind == 2):
            self.fragment(channel, body)


def verify_network(path):
    probe = Probe(json.loads(path.read_text()), 25565)
    try:
        probe.run()
    finally:
        probe.socket.close()
