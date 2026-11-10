import importlib.util
import subprocess
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location('release_script', Path(__file__).parents[1]/'scripts/release_firmware.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def setup_project(monkeypatch, tmp_path):
    monkeypatch.setattr(release, 'ROOT', tmp_path)
    monkeypatch.setattr(release.shutil, 'which', lambda name: '/test/pio')
    config = tmp_path/'firmware/include/config.h'
    config.parent.mkdir(parents=True)
    config.write_text('#define DEVICE_ID "desk-01"\n#define WIFI_SSID "real-network"\n')
    binary = tmp_path/'firmware/.pio/build/e1002/firmware.bin'
    binary.parent.mkdir(parents=True)
    binary.write_bytes(b'compiled-image')
    return binary


def test_compile_then_publish(monkeypatch, tmp_path):
    setup_project(monkeypatch, tmp_path)
    monkeypatch.setattr(release.sys, 'argv', ['release', '--device', 'desk-01', '--build', '2'])
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        if command[0] == '/test/pio':
            assert kwargs['env']['INK_FIRMWARE_BUILD'] == '2'
        if '--build' in command:
            assert kwargs['stdin'].read() == b'compiled-image'
    monkeypatch.setattr(release, 'run', run)
    release.main()
    assert calls[1][0] == '/test/pio'
    assert calls[2][-2:] == ['--build', '2']
    assert all('curl' not in cmd for cmd in calls)


def test_failed_compile_never_publishes(monkeypatch, tmp_path):
    setup_project(monkeypatch, tmp_path)
    monkeypatch.setattr(release.sys, 'argv', ['release', '--device', 'desk-01', '--build', '2'])
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        if command[0] == '/test/pio': raise subprocess.CalledProcessError(1, command)
    monkeypatch.setattr(release, 'run', run)
    with pytest.raises(subprocess.CalledProcessError): release.main()
    assert not any('--build' in cmd for cmd in calls)


def test_wrong_device_never_compiles(monkeypatch, tmp_path):
    setup_project(monkeypatch, tmp_path)
    monkeypatch.setattr(release.sys, 'argv', ['release', '--device', 'another', '--build', '2'])
    calls = []
    monkeypatch.setattr(release, 'run', lambda cmd, **kw: calls.append(cmd))
    with pytest.raises(SystemExit): release.main()
    assert len(calls) == 1


def test_withdraw_never_compiles(monkeypatch):
    monkeypatch.setattr(release.sys, 'argv', ['release', '--device', 'desk-01', '--withdraw'])
    calls = []
    monkeypatch.setattr(release, 'run', lambda cmd, **kw: calls.append(cmd))
    release.main()
    assert calls[-1][-1] == '--withdraw'
    assert not any('run' in cmd for cmd in calls)


@pytest.mark.parametrize('options', [[], ['--device', 'desk-01'], ['--build', '12']])
def test_automatic_defaults(monkeypatch, tmp_path, options):
    setup_project(monkeypatch, tmp_path)
    (tmp_path/'firmware/include/firmware_version.h').write_text('#define FIRMWARE_BUILD 5\n')
    monkeypatch.setattr(release.sys, 'argv', ['release']+options)
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        if '--reserve-build' in command:
            assert command[-2:] == ['--minimum', '5']
            return subprocess.CompletedProcess(command, 0, stdout='{"build":12}')
        if command[0] == '/test/pio':
            assert kwargs['env']['INK_FIRMWARE_BUILD'] == '12'
        if '--build' in command:
            assert command[-2:] == ['--build', '12']
            assert command[command.index('--device')+1] == 'desk-01'
    monkeypatch.setattr(release, 'run', run)
    release.main()
    assert any('--build' in cmd for cmd in calls)
    assert sum('--reserve-build' in cmd for cmd in calls) == (0 if '--build' in options else 1)


def test_automatic_device_for_withdraw(monkeypatch, tmp_path):
    setup_project(monkeypatch, tmp_path)
    monkeypatch.setattr(release.sys, 'argv', ['release', '--withdraw'])
    calls = []
    monkeypatch.setattr(release, 'run', lambda cmd, **kw: calls.append(cmd))
    release.main()
    assert calls[-1][-3:] == ['--device', 'desk-01', '--withdraw']
    assert not any('--reserve-build' in cmd for cmd in calls)


def test_build_reservations_are_global_and_concurrent(tmp_path):
    import sqlite3
    import sys
    from concurrent.futures import ThreadPoolExecutor
    sys.path.insert(0, str(Path(__file__).parents[1]/'server'))
    import ota
    from publish_firmware import reserve_build
    database = tmp_path/'pixel.db'
    with sqlite3.connect(database) as db:
        ota.init(db)
        db.execute('INSERT INTO firmware_releases VALUES(7,?,?)', ('digest', b'image'))
        db.execute('INSERT INTO firmware_devices(device_id,running_build) VALUES(?,11)', ('other',))
        db.commit()
        assert reserve_build(db, 1) == 12
        assert reserve_build(db, 15) == 16
    def reserve(_):
        with sqlite3.connect(database, timeout=10) as db:
            return reserve_build(db)
    with ThreadPoolExecutor(max_workers=4) as workers:
        assert sorted(workers.map(reserve, range(4))) == [17, 18, 19, 20]
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT COUNT(*) FROM firmware_releases').fetchone()[0] == 1
        assert db.execute('SELECT COUNT(*) FROM firmware_devices WHERE target IS NOT NULL').fetchone()[0] == 0
        with pytest.raises(ValueError): reserve_build(db, 999999999)
