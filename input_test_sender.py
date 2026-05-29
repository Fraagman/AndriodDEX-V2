"""
Test sender: sends protobuf InputEvent messages to Android DesktopAccessibilityService on port 55557.
Usage: python input_test_sender.py <android_ip>
"""
import socket, struct, sys, time

def encode_varint(value):
    """Encode an integer as a protobuf varint."""
    parts = []
    while value > 0x7F:
        parts.append((value & 0x7F) | 0x80)
        value >>= 7
    parts.append(value & 0x7F)
    return bytes(parts)

def encode_mouse_event(x, y, buttons, timestamp=0):
    """Encode a MouseEvent protobuf message."""
    buf = bytearray()
    # field 1 (x): tag = (1<<3)|0 = 0x08
    if x > 0:
        buf.append(0x08)
        buf.extend(encode_varint(x))
    # field 2 (y): tag = (2<<3)|0 = 0x10
    if y > 0:
        buf.append(0x10)
        buf.extend(encode_varint(y))
    # field 3 (buttons): tag = (3<<3)|0 = 0x18
    if buttons > 0:
        buf.append(0x18)
        buf.extend(encode_varint(buttons))
    # field 4 (timestamp): tag = (4<<3)|0 = 0x20
    if timestamp > 0:
        buf.append(0x20)
        buf.extend(encode_varint(timestamp))
    return bytes(buf)

def encode_input_event_mouse(x, y, buttons, timestamp=0):
    """Encode an InputEvent wrapping a MouseEvent."""
    mouse_bytes = encode_mouse_event(x, y, buttons, timestamp)
    buf = bytearray()
    # InputEvent field 1 (mouse): tag = (1<<3)|2 = 0x0A, then length, then MouseEvent
    buf.append(0x0A)
    buf.extend(encode_varint(len(mouse_bytes)))
    buf.extend(mouse_bytes)
    return bytes(buf)

def send_event(sock, event_bytes):
    """Send a length-prefixed protobuf message."""
    length = struct.pack('<I', len(event_bytes))
    sock.sendall(length + event_bytes)

def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else "10.214.143.135"
    port = 55557

    print(f"Connecting to {ip}:{port}...")
    s = socket.socket()
    s.connect((ip, port))
    print("Connected!")

    # Test 1: Move cursor to center (960, 540), no click
    print("Sending move to center (960, 540)...")
    send_event(s, encode_input_event_mouse(960, 540, 0))
    time.sleep(1)

    # Test 2: Click at center
    print("Sending click at center (960, 540)...")
    send_event(s, encode_input_event_mouse(960, 540, 1))
    time.sleep(0.1)
    send_event(s, encode_input_event_mouse(960, 540, 0))  # release
    time.sleep(1)

    # Test 3: Move to top-left
    print("Sending move to top-left (0, 0)...")
    send_event(s, encode_input_event_mouse(0, 0, 0))
    time.sleep(1)

    # Test 4: Move to bottom-right
    print("Sending move to bottom-right (1919, 1079)...")
    send_event(s, encode_input_event_mouse(1919, 1079, 0))
    time.sleep(1)

    # Test 5: Click at bottom-right
    print("Sending click at bottom-right (1919, 1079)...")
    send_event(s, encode_input_event_mouse(1919, 1079, 1))
    time.sleep(0.1)
    send_event(s, encode_input_event_mouse(1919, 1079, 0))
    time.sleep(1)

    # Test 6: Sweep cursor across screen
    print("Sweeping cursor diagonally...")
    for i in range(0, 1920, 40):
        x = i
        y = int(i * 1080 / 1920)
        send_event(s, encode_input_event_mouse(x, y, 0))
        time.sleep(0.03)

    print("Done! Check Logcat for 'Injecting click' messages and cursor movement.")
    s.close()

if __name__ == "__main__":
    main()
