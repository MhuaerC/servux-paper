"""Wire-level Easy Place and Syncmatica regressions, run only by the Actions server harness."""
import gzip
import hashlib
import io
import json
import struct
import time
import uuid
from network_probe import Probe, read_utf, read_varint, utf, varint


def blockpos(x, y, z):
    return struct.pack(">Q", ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff))


def file_hash(data):
    # Syncmatica UUID.nameUUIDFromBytes(MD5(file)), not a plain MD5 UUID.
    return uuid.UUID(bytes=hashlib.md5(hashlib.md5(data).digest()).digest(), version=3)


def position(x=10, rotation=1, mirror=2, extended=True):
    value = blockpos(x, 70, -20) + utf("minecraft:overworld") + struct.pack(">ii", rotation, mirror)
    if extended:
        value += struct.pack(">i", 1) + utf("main") + blockpos(2, 3, 4) + struct.pack(">ii", 2, 1)
    return value


def metadata(ident, data, extended=True):
    value = ident.bytes + utf("integration.litematic") + file_hash(data).bytes
    if extended:
        value += uuid.UUID(int=0).bytes + utf("forged_owner") + uuid.UUID(int=0).bytes + utf("forged_modifier")
    return value + position(extended=extended)


def read_position(stream, extended=True):
    result = {"pos": stream.read(8).hex(), "dimension": read_utf(stream),
              "rotation": struct.unpack(">i", stream.read(4))[0], "mirror": struct.unpack(">i", stream.read(4))[0]}
    if extended:
        result["regions"] = []
        for _ in range(struct.unpack(">i", stream.read(4))[0]):
            result["regions"].append((read_utf(stream), stream.read(8).hex(), *struct.unpack(">ii", stream.read(8))))
    return result


def read_metadata(payload, extended=True):
    stream = io.BytesIO(payload)
    result = {"id": str(uuid.UUID(bytes=stream.read(16))), "name": read_utf(stream),
              "hash": str(uuid.UUID(bytes=stream.read(16)))}
    if extended:
        result.update(owner=str(uuid.UUID(bytes=stream.read(16))), owner_name=read_utf(stream),
                      modifier=str(uuid.UUID(bytes=stream.read(16))), modifier_name=read_utf(stream))
    result["position"] = read_position(stream, extended)
    assert not stream.read(), "Trailing metadata"
    return result


class Client(Probe):
    def __init__(self, fixture, username):
        super().__init__(fixture, 25565, username)
        self.inbox = []
        self.sequence = 0
        self.run()  # Also exercises the existing MiniHUD protocol on each new connection.

    def receive_custom_payload(self, channel, body):
        if channel.startswith("syncmatica:"):
            # Validate every payload from the first login message, before the handshake.
            # Syncmatica 0.3.20 cannot decode legacy per-message outer channels.
            assert channel == "syncmatica:main", f"Client would receive DiscardedPayload: {channel}"
            message = read_utf(body)
            assert message.startswith("syncmatica:") and message != channel, message
            self.inbox.append((message, body.read()))
        elif channel == "servux:feature_test":
            self.inbox.append((channel, body.read()))
        else:
            super().receive_custom_payload(channel, body)

    def pump(self):
        name, body = self.read_packet()
        if "disconnect" in name:
            raise AssertionError(f"Disconnected: {body.read()!r}")
        if name in ("keep_alive", "ping"):
            self.send("keep_alive" if name == "keep_alive" else "pong", body.read())
        elif name == "player_position":
            teleport = read_varint(body.read)
            pos = body.read(24)
            body.read(24)
            rotation = body.read(8)
            self.send("accept_teleportation", varint(teleport) + pos + rotation)
            self.send("player_loaded")
        elif name == "custom_payload":
            self.receive_custom_payload(read_utf(body), body)
        return name, body

    def take(self, packet, ident=None):
        channel = packet if ":" in packet else "syncmatica:" + packet
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            for index, (key, payload) in enumerate(self.inbox):
                if key == channel and (ident is None or payload[:16] == ident.bytes):
                    return self.inbox.pop(index)[1]
            self.pump()
        raise TimeoutError(f"Missing {channel}, queued={[c for c, _ in self.inbox]}")

    def sync(self, packet, body=b""):
        self.custom("syncmatica:main", utf("syncmatica:" + packet) + body)

    def handshake(self, extended=True):
        # Like the real client, wait for the unsolicited server greeting first.
        version = read_utf(io.BytesIO(self.take("register_version")))
        assert version == "0.3.20-servux-paper", version
        assert "syncmatica:main" in self.advertised
        assert not any(c.startswith("syncmatica:") and c != "syncmatica:main" for c in self.advertised)
        self.sync("feature_request")
        features = read_utf(io.BytesIO(self.take("feature"))).split("\n")
        assert set(features) == {"CORE", "FEATURE", "MODIFY", "CORE_EX"}
        self.sync("register_version", utf("0.3.20"))
        self.take("feature_request")
        self.sync("feature", utf("CORE\nFEATURE\nMODIFY\nDISPLAY_NAME\nVERSION" + ("\nCORE_EX" if extended else "")))
        assert self.take("confirm_user") == struct.pack(">i", 0)
        snapshot = self.control(op="snapshot")
        self.player_uuid = snapshot["uuid"]
        assert snapshot["sync_codec_packets"] >= 4, snapshot

    def control(self, **request):
        self.custom("servux:feature_test", json.dumps(request).encode())
        return json.loads(self.take("servux:feature_test"))

    def upload(self, ident, data):
        self.sync("register_metadata", metadata(ident, data))
        assert self.take("request_download", ident) == ident.bytes
        for offset in range(0, len(data), 16384):
            chunk = data[offset:offset + 16384]
            self.sync("send_litematic", ident.bytes + struct.pack(">i", len(chunk)) + chunk)
            assert self.take("received_litematic", ident) == ident.bytes
        self.sync("finished_litematic", ident.bytes)
        result = read_metadata(self.take("register_metadata", ident))
        assert result["owner"] == self.player_uuid, result
        assert result["owner_name"] == self.username, result
        assert result["hash"] == str(file_hash(data)), result
        return result

    def download(self, ident):
        self.sync("request_download", ident.bytes)
        data = bytearray()
        while True:
            # Finish and chunks are mutually exclusive responses to the previous ACK.
            deadline = time.monotonic() + 15
            while not any(c in ("syncmatica:send_litematic", "syncmatica:finished_litematic")
                          and p[:16] == ident.bytes for c, p in self.inbox):
                assert time.monotonic() < deadline
                self.pump()
            for index, (channel, payload) in enumerate(self.inbox):
                if payload[:16] != ident.bytes:
                    continue
                if channel == "syncmatica:finished_litematic":
                    self.inbox.pop(index)
                    return bytes(data)
                if channel == "syncmatica:send_litematic":
                    self.inbox.pop(index)
                    size = struct.unpack(">i", payload[16:20])[0]
                    assert 0 < size <= 16384 and len(payload) == size + 20
                    data.extend(payload[20:])
                    self.sync("received_litematic", ident.bytes)
                    break

    def place(self, item, bits, expected, **options):
        self.control(op="prepare", item=item, deny=options.get("deny", False), **{k: v for k, v in options.items() if k != "deny"})
        self.sequence += 1
        # Match FriendlyByteBuf.writeBlockHitResult: pos, face, local hit XYZ, inside, border.
        x = options.get("far", False) and 1000 or 3
        packet = varint(int(options.get("offhand", False))) + blockpos(x, 100, 0) + varint(1)
        packet += struct.pack(">fff", bits + 2.5, 1.0, 0.5) + b"\x00\x00" + varint(self.sequence)
        self.send("use_item_on", packet)
        deadline = time.monotonic() + 10
        while True:
            assert time.monotonic() < deadline, "Missing placement acknowledgement"
            name, body = self.pump()
            if "ack" in name and "block" in name:
                if read_varint(body.read) >= self.sequence:
                    break
        result = self.control(op="snapshot")
        assert all(value in result["state"] for value in expected), (item, bits, options, result)
        rejected = options.get("deny") or options.get("cancel") or options.get("far")
        assert result["count"] == (5 if rejected or options.get("creative") else 4), result
        assert not result["temporary_component"], result
        if not options.get("deny") and not options.get("far"):
            assert result["events"] >= 1, result
        return result

    def verify_slot_order(self):
        self.control(op="prepare", item="OAK_LOG", deny=False)
        self.sequence += 1
        self.send("set_carried_item", struct.pack(">h", 0))
        packet = varint(0) + blockpos(3, 100, 0) + varint(1)
        packet += struct.pack(">fff", 2.5, 1.0, 0.5) + b"\x00\x00" + varint(self.sequence)
        self.send("use_item_on", packet)
        self.send("set_carried_item", struct.pack(">h", 1))
        deadline = time.monotonic() + 10
        while True:
            assert time.monotonic() < deadline
            name, body = self.pump()
            if "ack" in name and "block" in name and read_varint(body.read) >= self.sequence:
                break
        result = self.control(op="snapshot")
        assert "oak_log" in result["state"] and "axis=x" in result["state"], result
        assert result["slot0_count"] == 4 and result["slot1_count"] == 5 and result["selected_slot"] == 1, result


def verify_features(path, resumed=False):
    fixture = json.loads(path.read_text())
    expected_path = path.parent / "feature-expected.json"
    owner = Client(fixture, "FeatureOwner")
    guest = None
    try:
        owner.handshake()
        if resumed:
            expected = json.loads(expected_path.read_text())
            ident = uuid.UUID(expected["id"])
            restored = read_metadata(owner.take("register_metadata", ident))
            assert restored["position"]["pos"] == blockpos(25, 70, -20).hex(), restored
            assert hashlib.sha256(owner.download(ident)).hexdigest() == expected["sha256"]
            owner.sync("remove_syncmatic", ident.bytes)
            owner.take("remove_syncmatic", ident)
            print("SERVUX_FEATURE_RESTART_OK: persisted file, ownership, position, rotation and subregions", flush=True)
            return

        guest = Client(fixture, "FeatureGuest")
        guest.handshake()
        # Independent encoded requests, not a call to the Java decoder under test.
        owner.place("OAK_LOG", 0, ["oak_log", "axis=x"])
        owner.place("OBSERVER", 8, ["observer", "facing=west"], offhand=True)
        owner.place("OAK_STAIRS", 4, ["oak_stairs", "facing=north", "half=top"])
        owner.place("REPEATER", 58, ["repeater", "delay=4", "facing=east"])
        owner.place("OAK_SLAB", 4, ["oak_slab", "type=bottom"])
        owner.place("OAK_LOG", 0, ["oak_log", "axis=x"], creative=True)
        owner.place("OAK_LOG", 0, ["air"], cancel=True)
        owner.place("OAK_LOG", 0, ["air"], deny=True)
        owner.place("OAK_LOG", 0, ["air"], far=True)
        bed = owner.place("RED_BED", 10, ["red_bed", "facing=east", "part=foot"])
        assert "part=head" in bed["east"] and "facing=east" in bed["east"], bed
        owner.place("OAK_STAIRS", 4, ["oak_stairs", "waterlogged=true"], water=True)
        owner.verify_slot_order()
        print("SERVUX_EASY_PLACE_OK: V3, survival/creative, offhand, multi-block, water, permission, reach, event cancellation", flush=True)

        # Opaque, valid GZIP/NBT schematic-shaped file spanning multiple 16 KiB chunks.
        nbt = b"\x0a\x00\x00\x07\x00\x04Data" + struct.pack(">i", 65536)
        nbt += b"".join(hashlib.sha256(str(i).encode()).digest() for i in range(2048)) + b"\x00"
        data = gzip.compress(nbt, mtime=0)
        ident = uuid.uuid4()
        original = owner.upload(ident, data)
        assert read_metadata(guest.take("register_metadata", ident)) == original
        assert guest.download(ident) == data
        guest.sync("modify_request", ident.bytes)
        guest.take("modify_request_deny", ident)
        guest.sync("remove_syncmatic", ident.bytes)
        assert read_metadata(guest.take("register_metadata", ident))["id"] == str(ident)
        owner.sync("modify_request", ident.bytes)
        owner.take("modify_request_accept", ident)
        owner.sync("modify_finish", ident.bytes + position(x=25, rotation=3, mirror=1))
        for client in (owner, guest):
            moved = io.BytesIO(client.take("modify", ident))
            assert moved.read(16) == ident.bytes
            target = read_position(moved)
            assert target["pos"] == blockpos(25, 70, -20).hex() and target["rotation"] == 3 and target["mirror"] == 1
            assert str(uuid.UUID(bytes=moved.read(16))) == owner.player_uuid

        # Duplicate IDs, no-share permission, corrupt hash, and malformed chunk sizes.
        owner.sync("register_metadata", metadata(ident, data))
        owner.take("cancel_share", ident)
        guest.control(op="permission", node="servux.syncmatica.share", value=False)
        rejected_id = uuid.uuid4()
        guest.sync("register_metadata", metadata(rejected_id, data))
        guest.take("cancel_share", rejected_id)
        for bad_size in (None, -1, 20000):
            bad_id = uuid.uuid4()
            owner.sync("register_metadata", metadata(bad_id, b"different hash"))
            owner.take("request_download", bad_id)
            if bad_size is None:
                owner.sync("send_litematic", bad_id.bytes + struct.pack(">i", 3) + b"bad")
                owner.take("received_litematic", bad_id)
                owner.sync("finished_litematic", bad_id.bytes)
            else:
                owner.sync("send_litematic", bad_id.bytes + struct.pack(">i", bad_size))
            owner.take("cancel_share", bad_id)

        # Another placement may reuse the same file without another upload.
        alias = uuid.uuid4()
        owner.sync("register_metadata", metadata(alias, data))
        owner.take("register_metadata", alias)
        guest.take("register_metadata", alias)
        owner.sync("remove_syncmatic", alias.bytes)
        owner.take("remove_syncmatic", alias)
        guest.take("remove_syncmatic", alias)
        assert guest.download(ident) == data
        expected_path.write_text(json.dumps({"id": str(ident), "sha256": hashlib.sha256(data).hexdigest()}))
        print("SERVUX_SYNCMATICA_OK: 0.3.20 client codec, login envelope, two clients, file hash, subregions, broadcast, modify, ownership, corruption, deduplication", flush=True)
    finally:
        owner.socket.close()
        if guest is not None:
            guest.socket.close()
