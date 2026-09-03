#!/usr/bin/env python3
"""Clean-room Switchboard record encoder matching the v10022 Android probe.

The encoder is independent of Google Health code. It exists so the Frida probe
can POST the same official-shaped record through Google Health's already
authorized GoldenGateCommandHandler instead of a second BOOTSTRAP session.
"""

from __future__ import annotations

from dataclasses import dataclass

RECORD_VERSION = "0.4.10"
DEFAULT_TTL_SECONDS = 60
MAX_LOCAL_ID = 0x3FFFFFFF
CHILD_ID_COUNT = 4
MOBILE_ID_NAMESPACE = 0x80000000
EVENT_SHOW = 0x30
SCHEDULER_TRIGGER_IMMEDIATE = 0
TTL_POLICY_POSIX_TIMESTAMP = 2
DEFAULT_APP_COLOR = 0xFFB0B0B0
CREATE_PATH = "sb/records"
DELETE_PATH = "sb/delete-records"


class ProtoWriter:
    def __init__(self) -> None:
        self._buffer = bytearray()

    def write_uint32(self, field_number: int, value: int) -> None:
        if value < 0 or value > 0xFFFFFFFF:
            raise ValueError("uint32 out of range")
        self._write_tag(field_number, 0)
        self._write_varint(value)

    def write_uint64(self, field_number: int, value: int) -> None:
        if value < 0:
            raise ValueError("uint64 must not be negative")
        self._write_tag(field_number, 0)
        self._write_varint(value)

    def write_fixed32(self, field_number: int, value: int) -> None:
        self._write_tag(field_number, 5)
        self._buffer.extend(int(value & 0xFFFFFFFF).to_bytes(4, "little"))

    def write_string(self, field_number: int, value: str) -> None:
        self.write_bytes(field_number, value.encode("utf-8"))

    def write_bytes(self, field_number: int, value: bytes) -> None:
        self._write_tag(field_number, 2)
        self._write_varint(len(value))
        self._buffer.extend(value)

    def to_bytes(self) -> bytes:
        return bytes(self._buffer)

    def _write_tag(self, field_number: int, wire_type: int) -> None:
        if field_number <= 0:
            raise ValueError("field number must be positive")
        self._write_varint((field_number << 3) | wire_type)

    def _write_varint(self, value: int) -> None:
        remaining = value
        while True:
            if remaining & ~0x7F == 0:
                self._buffer.append(remaining)
                return
            self._buffer.append((remaining & 0x7F) | 0x80)
            remaining >>= 7


def mobile_id(local_id: int) -> int:
    return MOBILE_ID_NAMESPACE | (local_id & MAX_LOCAL_ID)


@dataclass(frozen=True)
class EncodedNotification:
    root_id: int
    record: bytes
    delete_command: bytes
    expires_at_epoch_seconds: int

    @property
    def unsigned_root_id(self) -> int:
        return self.root_id & 0xFFFFFFFF


def encode_default_notification(
    *,
    id_seed: int,
    app_id: str,
    app_name: str,
    title: str,
    content: str,
    timestamp_epoch_seconds: int,
    expires_at_epoch_seconds: int | None = None,
    haptic_pattern: int | None = None,
) -> EncodedNotification:
    if id_seed < 1 or id_seed > MAX_LOCAL_ID - CHILD_ID_COUNT:
        raise ValueError("id_seed is outside the mobile id range")
    if not app_id.strip():
        raise ValueError("app_id must not be blank")
    if timestamp_epoch_seconds < 0:
        raise ValueError("timestamp must not be negative")
    if expires_at_epoch_seconds is None:
        expires_at_epoch_seconds = timestamp_epoch_seconds + DEFAULT_TTL_SECONDS
    if expires_at_epoch_seconds <= timestamp_epoch_seconds:
        raise ValueError("expiry must follow timestamp")
    if haptic_pattern is not None and haptic_pattern < 0:
        raise ValueError("haptic_pattern must be unsigned")

    root_id = mobile_id(id_seed)
    popup_expression_id = mobile_id(id_seed + 1)
    scheduler_configuration_id = mobile_id(id_seed + 2)
    ttl_configuration_id = mobile_id(id_seed + 3)
    notification_common_expression_id = mobile_id(id_seed + 4)

    scheduler_event = ProtoWriter()
    scheduler_event.write_uint32(1, EVENT_SHOW)
    scheduler_event.write_fixed32(2, popup_expression_id)

    scheduler = ProtoWriter()
    scheduler.write_uint32(1, SCHEDULER_TRIGGER_IMMEDIATE)
    scheduler.write_bytes(2, scheduler_event.to_bytes())

    scheduler_configuration = ProtoWriter()
    scheduler_configuration.write_fixed32(1, scheduler_configuration_id)
    scheduler_configuration.write_bytes(32, scheduler.to_bytes())

    ttl = ProtoWriter()
    ttl.write_uint32(1, TTL_POLICY_POSIX_TIMESTAMP)
    ttl.write_uint64(3, expires_at_epoch_seconds)

    ttl_configuration = ProtoWriter()
    ttl_configuration.write_fixed32(1, ttl_configuration_id)
    ttl_configuration.write_bytes(33, ttl.to_bytes())

    common = ProtoWriter()
    common.write_string(1, app_id)
    common.write_uint32(2, DEFAULT_APP_COLOR)
    if app_name:
        common.write_string(4, app_name)
    if title:
        common.write_string(5, title)
    if content:
        common.write_string(6, content)
    common.write_uint64(7, timestamp_epoch_seconds)
    if haptic_pattern is not None:
        common.write_uint32(10, haptic_pattern)

    common_expression = ProtoWriter()
    common_expression.write_fixed32(1, notification_common_expression_id)
    common_expression.write_bytes(36, common.to_bytes())

    popup_template = ProtoWriter()
    popup_template.write_uint32(1, notification_common_expression_id & 0xFFFFFFFF)

    popup = ProtoWriter()
    popup.write_bytes(4, popup_template.to_bytes())

    popup_expression = ProtoWriter()
    popup_expression.write_fixed32(1, popup_expression_id)
    popup_expression.write_bytes(34, popup.to_bytes())

    record = ProtoWriter()
    record.write_fixed32(1, root_id)
    record.write_string(2, RECORD_VERSION)
    record.write_bytes(3, scheduler_configuration.to_bytes())
    record.write_bytes(3, ttl_configuration.to_bytes())
    record.write_bytes(4, common_expression.to_bytes())
    record.write_bytes(4, popup_expression.to_bytes())

    delete_command = ProtoWriter()
    delete_command.write_uint32(1, root_id & 0xFFFFFFFF)

    return EncodedNotification(
        root_id=root_id,
        record=record.to_bytes(),
        delete_command=delete_command.to_bytes(),
        expires_at_epoch_seconds=expires_at_epoch_seconds,
    )


def to_hex(data: bytes) -> str:
    return data.hex().upper()
