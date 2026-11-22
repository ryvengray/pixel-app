"""Exercise the real device's playtest flow, then clear only playtest progress."""
import argparse
import time
import serial

parser = argparse.ArgumentParser()
parser.add_argument('--port', required=True)
args = parser.parse_args()
link = serial.Serial(port=None, baudrate=115200, timeout=0.1)
link.dtr = False
link.rts = False
link.port = args.port
link.open()

def expect(marker, timeout=8):
    deadline = time.monotonic() + timeout
    lines = []
    while time.monotonic() < deadline:
        line = link.readline().decode('utf-8', errors='replace').strip()
        if line:
            print(line, flush=True)
            lines.append(line)
            if 'TEXT OVERFLOW' in line or 'Guru Meditation' in line:
                raise RuntimeError(line)
            if marker in line:
                return
    raise RuntimeError(f'Timed out waiting for {marker!r}: {lines}')

def send(command, marker):
    link.write(command.encode('ascii'))
    expect(marker)

try:
    link.reset_input_buffer()
    send('0', 'PAGE 0')
    send('x', 'solved=0 skipped=0')
    send('n', 'PAGE 1')
    send('a', 'PAGE 2')
    send('a', 'PAGE 4')
    send('r', 'PAGE 2')
    send('t', 'PAGE 3')
    send('r', 'PAGE 2')
    send('b', 'PAGE 5 solved=1')
    send('n', 'PAGE 1')
    send('b', 'PAGE 2')
    send('s', 'PAGE 1 solved=1 skipped=1')
    send('c', 'PAGE 2')
    send('a', 'PAGE 5 solved=2 skipped=1')
    send('n', 'PAGE 6 solved=2 skipped=1')
    link.rts = True
    time.sleep(0.15)
    link.rts = False
    expect('PLAYTEST READY', timeout=12)
    send('0', 'PAGE 0 solved=2 skipped=1')
    send('1', 'PAGE 1')
    send('b', 'PAGE 2')
    send('c', 'PAGE 5 solved=3 skipped=0')
    send('n', 'PAGE 6 solved=3 skipped=0')
    send('0', 'PAGE 0')
    send('1', 'PAGE 1')
    send('a', 'PAGE 5 solved=3')
    send('x', 'PAGE 0 solved=0 skipped=0')
    send('C', 'CALIBRATE point=1')
    print('PASS: retry, hint, skip, completion, persistence, review and reset. Touch calibration still requires physical taps.')
finally:
    link.close()
