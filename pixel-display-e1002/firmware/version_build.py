"""Inject a release number without changing tracked source or board flags."""
import os
Import('env')
build = os.environ.get('INK_FIRMWARE_BUILD')
if build is not None:
    if not build.isdigit() or not 1 <= int(build) <= 999999999:
        raise ValueError('INK_FIRMWARE_BUILD must be 1..999999999')
    env.Append(CPPDEFINES=[('FIRMWARE_BUILD', int(build))])
