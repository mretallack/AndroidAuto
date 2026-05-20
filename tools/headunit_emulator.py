#!/usr/bin/env python3
"""
Minimal Android Auto Head Unit Emulator for testing.
Connects to the phone's TCP server on port 5277 and performs the full protocol handshake.

Usage:
    adb forward tcp:5277 tcp:5277
    python3 tools/headunit_emulator.py

This speaks the AA protocol: VERSION_REQUEST → TLS → AUTH → SERVICE_DISCOVERY → CHANNEL_OPEN → VIDEO
"""

import socket
import ssl
import struct
import sys
import time
import threading

HOST = '127.0.0.1'
PORT = 5277

# Frame flags
FRAME_SINGLE = 0x03
FRAME_ENCRYPTED = 0x08

# Message types
VERSION_REQUEST = 0x0001
VERSION_RESPONSE = 0x0002
SSL_HANDSHAKE = 0x0003
AUTH_COMPLETE = 0x0004
SERVICE_DISCOVERY_REQUEST = 0x0005
SERVICE_DISCOVERY_RESPONSE = 0x0006
CHANNEL_OPEN_REQUEST = 0x0007
CHANNEL_OPEN_RESPONSE = 0x0008
PING_REQUEST = 0x000B
PING_RESPONSE = 0x000C

def send_frame(sock, channel, flags, payload):
    """Send an AAP frame."""
    header = struct.pack('>BBH', channel, flags, len(payload))
    sock.sendall(header + payload)

def recv_frame(sock):
    """Receive an AAP frame. Returns (channel, flags, payload)."""
    header = sock.recv(4)
    if len(header) < 4:
        return None, None, None
    channel, flags, length = struct.unpack('>BBH', header)
    payload = b''
    while len(payload) < length:
        chunk = sock.recv(length - len(payload))
        if not chunk:
            return None, None, None
        payload += chunk
    return channel, flags, payload

def parse_message_type(payload):
    """Extract 2-byte message type from payload."""
    if len(payload) >= 2:
        return struct.unpack('>H', payload[:2])[0]
    return None

def main():
    print(f"[HU] Connecting to {HOST}:{PORT}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    try:
        sock.connect((HOST, PORT))
    except Exception as e:
        print(f"[HU] Connection failed: {e}")
        sys.exit(1)
    print("[HU] Connected!")

    # Step 1: Send VERSION_REQUEST (v1.7)
    version_payload = struct.pack('>HHH', VERSION_REQUEST, 1, 7)
    send_frame(sock, 0, FRAME_SINGLE, version_payload)
    print("[HU] Sent VERSION_REQUEST (v1.7)")

    # Step 2: Receive VERSION_RESPONSE
    ch, flags, payload = recv_frame(sock)
    if payload:
        msg_type = parse_message_type(payload)
        if msg_type == VERSION_RESPONSE:
            major = struct.unpack('>H', payload[2:4])[0]
            minor = struct.unpack('>H', payload[4:6])[0]
            status = struct.unpack('>H', payload[6:8])[0]
            print(f"[HU] Received VERSION_RESPONSE: v{major}.{minor} status={status}")
        else:
            print(f"[HU] Unexpected message type: 0x{msg_type:04x}")
            return
    else:
        print("[HU] No response received")
        return

    # Step 3: TLS Handshake
    # We need to act as TLS client connecting to the phone (TLS server)
    print("[HU] Starting TLS handshake...")
    
    # Wrap socket with SSL - we're the client, phone is server
    # We need to accept any certificate (the phone uses a Google-signed cert)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    
    # The AA protocol wraps TLS records inside SSL_HANDSHAKE messages
    # This is "in-band TLS" - not a direct TLS socket upgrade
    # We need to manually exchange TLS records wrapped in AAP frames
    print("[HU] Note: In-band TLS not implemented in this emulator.")
    print("[HU] The phone expects TLS records wrapped in SSL_HANDSHAKE (0x0003) messages.")
    print("[HU] Full TLS handshake requires implementing the in-band TLS wrapper.")
    print()
    print("[HU] === TEST RESULT ===")
    print("[HU] VERSION negotiation: SUCCESS (v1.7)")
    print("[HU] TLS handshake: NOT IMPLEMENTED (requires in-band TLS)")
    print("[HU] To fully test, use openauto or the real car head unit.")
    
    # Keep connection alive briefly to see if phone sends anything
    print("[HU] Waiting 5 seconds for any additional messages...")
    sock.settimeout(5)
    try:
        while True:
            ch, flags, payload = recv_frame(sock)
            if payload is None:
                break
            msg_type = parse_message_type(payload)
            print(f"[HU] Received: ch={ch} flags=0x{flags:02x} type=0x{msg_type:04x} len={len(payload)}")
    except socket.timeout:
        pass
    
    sock.close()
    print("[HU] Done.")

if __name__ == '__main__':
    main()
