import socket, struct

s = socket.socket()
s.bind(('0.0.0.0', 55556))
s.listen(1)
print('Listening on 55556...')
c, a = s.accept()
print(f'Connected to {a}')

while True:
    data = c.recv(4)
    if not data:
        break
    l = struct.unpack('>I', data)[0]
    
    # We must loop recv because the frame is 8MB and TCP splits packets
    frame_data = bytearray()
    while len(frame_data) < l:
        chunk = c.recv(min(l - len(frame_data), 4096 * 1024))
        if not chunk: break
        frame_data.extend(chunk)
        
    print(f'Received frame: {len(frame_data)} bytes')
