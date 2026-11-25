package com.x4tether.bridge

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7b8e1000-4a3c-4f21-9c5d-8e7a6b5c4d00")

    // Legacy TetherCtl Characteristics (100% backward-compatible)
    val CHR_ON_UUID: UUID = UUID.fromString("7b8e1001-4a3c-4f21-9c5d-8e7a6b5c4d00")
    val CHR_OFF_UUID: UUID = UUID.fromString("7b8e1002-4a3c-4f21-9c5d-8e7a6b5c4d00")
    val CHR_STATUS_UUID: UUID = UUID.fromString("7b8e1003-4a3c-4f21-9c5d-8e7a6b5c4d00")

    // X4BP v1 BLE HTTP Proxy Characteristics
    val CHR_REQ_UUID: UUID = UUID.fromString("7b8e1004-4a3c-4f21-9c5d-8e7a6b5c4d00")
    val CHR_RESP_UUID: UUID = UUID.fromString("7b8e1005-4a3c-4f21-9c5d-8e7a6b5c4d00")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Central -> Peripheral commands on CHR_REQ
    const val REQ_FLAG_FIRST = 0x01
    const val REQ_FLAG_LAST = 0x02
    const val CMD_RX_ACK = 0x81
    const val CMD_CANCEL = 0x82

    // Peripheral -> Central notifications on CHR_RESP
    const val PKT_READY: Byte = 0x00
    const val PKT_REQ_ACK: Byte = 0x01
    const val PKT_HEARTBEAT: Byte = 0x02
    const val PKT_RESP_META: Byte = 0x10
    const val PKT_RESP_DATA: Byte = 0x11

    const val RESP_FLAG_ZLIB = 0x01
    const val RESP_FLAG_ERR = 0x02

    // Flow control: 10 packets per window (safely under X4 Pro's 32-notification hardware queue)
    const val WINDOW_SIZE = 10
    const val MAX_HTTP_BODY_BYTES = 262144

    // Broadcast actions received by MacroDroid ("Intent Received" trigger)
    const val ACTION_TETHER_ON = "com.x4tether.ACTION_TETHER_ON"
    const val ACTION_TETHER_OFF = "com.x4tether.ACTION_TETHER_OFF"

    // Internal UI update broadcast
    const val ACTION_LOG_UPDATED = "com.x4tether.bridge.LOG_UPDATED"
}
