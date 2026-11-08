import struct
import pytest
from server.realtime_protocol import touch_packet


def test_scrcpy_touch_binary_layout():
    packet=touch_packet(0,120,240,576,1280)
    assert len(packet)==32
    assert struct.unpack('>BBqiiHHHII',packet)==(2,0,0,120,240,576,1280,65535,0,0)
    assert struct.unpack('>BBqiiHHHII',touch_packet(1,120,240,576,1280))[7]==0
    assert struct.unpack('>BBqiiHHHII',touch_packet(3,120,240,576,1280))[1]==3


@pytest.mark.parametrize('args',[(4,0,0,576,1280),(0,-1,0,576,1280),(0,576,0,576,1280),(0,0,1280,576,1280),(0,0,0,0,1280),(0,True,0,576,1280)])
def test_invalid_realtime_coordinates_rejected(args):
    with pytest.raises(ValueError):touch_packet(*args)
