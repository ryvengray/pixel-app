"""Small, version-pinned scrcpy 3.3.4 touch protocol helpers."""
import struct


def touch_packet(action, x, y, width, height):
    if action not in (0,1,2,3):raise ValueError('invalid touch action')
    if any(type(v) is not int for v in (x,y,width,height)):raise ValueError('integer coordinates required')
    if not 1<=width<=8192 or not 1<=height<=8192 or not 0<=x<width or not 0<=y<height:
        raise ValueError('coordinates outside current video')
    # type, Android action, finger pointer 0, position, pressure, button, buttons.
    return struct.pack('>BBqiiHHHII',2,action,0,x,y,width,height,0 if action in (1,3) else 65535,0,0)
