import argparse
import os
import tempfile
import unittest
from contextlib import contextmanager
from unittest.mock import patch
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import flash_service as f

class TransportTests(unittest.TestCase):
    def test_direct_transfer_checks_status(self):
        class Device:
            def __init__(self): self.blocks = []; self.commands = []
            def start_download(self, target, name, fmt, size, timeout):
                self.start = (target, name, fmt, size); return b'success'
            def write_download_block(self, seq, data, timeout): self.blocks.append((seq, len(data)))
            def bulkCmd(self, cmd, timeout): self.commands.append(cmd); return b'success'
        device = Device()
        @contextmanager
        def opened(): yield device
        with tempfile.NamedTemporaryFile(delete=False) as image:
            image.write(b'x' * 150000); path = image.name
        try:
            with patch.object(f, 'open_worldcup', opened):
                f.download_image('store', 'system', path)
                self.assertEqual(f.aml_bulkcmd('saveenv'), 'success')
            self.assertEqual(device.start, ('store', 'system', 'normal', 150000))
            self.assertEqual(device.blocks, [(0, 65536), (1, 65536), (2, 18928)])
            self.assertEqual(device.commands, ['download get_status', 'saveenv'])
        finally:
            os.unlink(path)

    def test_usb_probe_tries_next_backend_and_records_working_one(self):
        from pyamlboot_local import proto
        class Usb:
            def get_active_configuration(self): return object()
        class Device:
            def __init__(self, backend):
                if backend == 'bad': raise ValueError('device not found')
                self.dev = Usb()
            def identify(self): return ''.join(map(chr, (0, 7, 0, 16)))
            def bulkCmd(self, command, timeout):
                assert command == 'mmc dev 1'
                return b'success'
            def close(self): pass
        with patch.dict(os.environ, {}, clear=False), patch.object(f, 'check_usb_device', return_value={'usb_service': 'libusbk'}), patch.object(f, 'get_pyusb_backends', return_value=[('libusb0', 'bad'), ('libusb1', 'good')]), patch.object(proto, 'AmlogicSoC', side_effect=lambda backend: Device(backend)):
            self.assertTrue(f.probe_burn_mode())
            self.assertEqual(os.environ['OPENSTATION_USB_BACKEND'], 'libusb1')

    def test_bootrom_is_not_sent_burn_commands(self):
        from pyamlboot_local import proto
        class Device:
            def __init__(self): self.dev = self; self.calls = []
            def get_active_configuration(self): return object()
            def identify(self): return ''.join(map(chr, (3, 2, 0, 0)))
            def bulkCmd(self, *args): self.calls.append(args); return b'success'
            def close(self): pass
        device = Device()
        with patch.object(f, 'check_usb_device', return_value={}), patch.object(f, 'get_pyusb_backends', return_value=[('libusb1', object())]), patch.object(proto, 'AmlogicSoC', return_value=device):
            self.assertFalse(f.probe_burn_mode())
            self.assertEqual(device.calls, [])

    def test_bootstrap_runs_only_for_confirmed_bootrom(self):
        stages = [{'rom': (3, 2), 'stage': (0, 0), 'mode': 'bootrom'}] * 2
        with tempfile.TemporaryDirectory() as folder:
            open(os.path.join(folder, 'aml_00053.img'), 'wb').close()
            with patch.object(f, 'SCRIPT_DIR', folder), patch.object(f, 'probe_burn_mode', side_effect=[False, True]), patch.object(f, 'check_usb_device', return_value={'usb_service': 'libusb0'}), patch.object(f, 'probe_usb_identify', side_effect=stages), patch.object(f.subprocess, 'run', return_value=argparse.Namespace(returncode=0)) as worker, patch.object(f.time, 'sleep'):
                self.assertTrue(f.ensure_burn_mode())
                self.assertIn('--usb-boot-step', worker.call_args.args[0])
        with patch.object(f, 'probe_burn_mode', return_value=False), patch.object(f, 'check_usb_device', return_value={}), patch.object(f, 'probe_usb_identify', return_value={'rom': (0, 7), 'stage': (0, 16), 'mode': 'uboot'}), patch.object(f.subprocess, 'run') as worker:
            self.assertFalse(f.ensure_burn_mode())
            worker.assert_not_called()

    def test_bootstrap_initializes_g12_then_sends_valid_blocks(self):
        from pyamlboot_local import proto
        class Device:
            def __init__(self): self.dev = self; self.events = []; self.requests = iter([(3, 65536), (3, 65536)])
            def set_configuration(self): pass
            def identify(self): return ''.join(map(chr, (3, 2, 0, 0)))
            def writeSimpleMemory(self, addr, data): self.events.append(('ddr', int.from_bytes(data, 'little')))
            def writeLargeMemory(self, addr, data, size): self.events.append(('bl2', len(data)))
            def run(self, addr): self.events.append(('run', addr))
            def getBootAMLC(self): return next(self.requests)
            def writeAMLCData(self, seq, offset, data): self.events.append(('block', seq, offset, len(data)))
            def close(self): pass
        device = Device()
        with tempfile.NamedTemporaryFile(delete=False) as image:
            image.write(b'x' * 70000); path = image.name
        try:
            with patch.object(f, 'get_pyusb_backends', return_value=[('fake', object())]), patch.object(proto, 'AmlogicSoC', return_value=device), patch.object(f.time, 'sleep'):
                self.assertEqual(f.run_usb_boot_step_inproc(path), 0)
            self.assertEqual(device.events[:4], [('ddr', 0xb1), ('ddr', 0x5183), ('ddr', 0xb1), ('ddr', 0x5183)])
            self.assertEqual(device.events[4:], [('bl2', 65536), ('run', 0xfffa0000), ('block', 0, 65536, 3)])
        finally:
            os.unlink(path)

    def test_empty_usb_status_waits_for_confirmation_without_resending_block(self):
        from pyamlboot_local.proto import AmlogicSoC
        class Usb:
            def __init__(self, replies): self.replies = iter(replies); self.controls = 0; self.writes = 0
            def ctrl_transfer(self, *args): self.controls += 1
            def write(self, endpoint, data, timeout): self.writes += 1; return len(data)
            def read(self, endpoint, size, timeout): return next(self.replies)
        soc = AmlogicSoC.__new__(AmlogicSoC)
        soc.dev = Usb([b'', b'Continue:32', b'OK!!'])
        with patch.object(f.time, 'sleep'):
            soc.write_download_block(1978, b'example', 3000)
        self.assertEqual((soc.dev.controls, soc.dev.writes), (1, 1))
        soc.dev = Usb([b'', b'', b''])
        with patch.object(f.time, 'sleep'), self.assertRaisesRegex(IOError, 'write state is unknown'):
            soc.write_download_block(1978, b'example', 3000)
        self.assertEqual((soc.dev.controls, soc.dev.writes), (1, 1))

    def test_null_status_records_actual_read_size(self):
        from pyamlboot_local.proto import AmlogicSoC
        class Usb:
            def read(self, endpoint, size, timeout): return b"\0" * 8
        soc = AmlogicSoC.__new__(AmlogicSoC)
        soc.dev = Usb()
        with patch.object(f.time, "sleep"), self.assertRaisesRegex(IOError, "last USB read: 8 bytes"):
            soc._read_burn_status(3000)

    def test_misc_failure_runs_after_ubt_and_reports_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ('vbmeta.img', 'vendor.img', 'system.img'):
                open(os.path.join(folder, name), 'wb').close()
            calls = []
            def flash(name, *args):
                calls.append(name)
                if name == 'misc': raise RuntimeError('misc failed')
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=flash), patch.object(f, 'run_after_ubt', side_effect=lambda: calls.append('after_ubt')):
                with self.assertRaisesRegex(RuntimeError, 'misc failed'):
                    f.run_flash(argparse.Namespace(rom_dir=folder, revision='0008'))
            self.assertEqual(calls, ['vbmeta', 'vendor', 'system', 'misc', 'after_ubt'])

    def test_after_ubt_runs_only_after_all_partitions_succeed(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ('vbmeta.img', 'vendor.img', 'system.img'):
                open(os.path.join(folder, name), 'wb').close()
            args = argparse.Namespace(rom_dir=folder)
            calls = []
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=lambda name, *args: calls.append(name)), patch.object(f, 'run_after_ubt', side_effect=lambda: calls.append('after_ubt')):
                f.run_flash(args)
            self.assertEqual(calls, ['vbmeta', 'vendor', 'system', 'misc', 'after_ubt'])
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=RuntimeError('failed')), patch.object(f, 'run_after_ubt') as finish:
                with self.assertRaises(RuntimeError): f.run_flash(args)
                finish.assert_not_called()

    def test_verify_rom_uses_manifest_when_present_and_rejects_mismatch(self):
        import hashlib
        import json
        with tempfile.TemporaryDirectory() as folder:
            payloads = {
                'vbmeta.img': b'vbm-test-bytes',
                'vendor.img': b'vnd-test-bytes',
                'system.img': b'sys-test-bytes',
            }
            files_meta = {}
            for key, fn in (('vbmeta', 'vbmeta.img'), ('vendor', 'vendor.img'), ('system', 'system.img')):
                data = payloads[fn]
                with open(os.path.join(folder, fn), 'wb') as out:
                    out.write(data)
                files_meta[key] = {
                    'fileName': fn,
                    'size': len(data),
                    'sha256': hashlib.sha256(data).hexdigest(),
                }
            manifest_path = os.path.join(folder, 'rom_manifest.json')
            with open(manifest_path, 'w', encoding='utf-8') as mf:
                json.dump({'version': '1.6.1', 'files': files_meta}, mf)

            f.verify_rom(folder)

            with open(os.path.join(folder, 'system.img'), 'wb') as out:
                out.write(b'corrupted!!!!!')
            with self.assertRaisesRegex(RuntimeError, 'SHA-256 system.img'):
                f.verify_rom(folder)

    def test_ensure_burn_mode_resets_usb_and_falls_back_to_update_bl2_boot(self):
        with tempfile.TemporaryDirectory() as folder:
            open(os.path.join(folder, 'aml_00053.img'), 'wb').close()
            open(os.path.join(folder, 'update.exe'), 'wb').close()
            bootrom = {'rom': (3, 2), 'stage': (0, 0), 'mode': 'bootrom'}
            calls = []

            def fake_run(cmd, **kwargs):
                calls.append(cmd)
                if '--usb-boot-step' in cmd:
                    return argparse.Namespace(returncode=1, stdout=b'', stderr=b'')
                if len(cmd) >= 2 and cmd[1] == 'bl2_boot':
                    return argparse.Namespace(returncode=0, stdout=b'ok', stderr=b'')
                return argparse.Namespace(returncode=0, stdout=b'', stderr=b'')

            with patch.object(f, 'SCRIPT_DIR', folder), \
                 patch.object(f, 'probe_burn_mode', side_effect=[False, False, False, False, False, False, False, True]), \
                 patch.object(f, 'check_usb_device', return_value={'usb_service': 'libusb0'}), \
                 patch.object(f, 'probe_usb_identify', return_value=bootrom), \
                 patch.object(f, 'warn_conflicting_usb_tools'), \
                 patch.object(f, 'reset_usb_device', return_value=True) as reset_mock, \
                 patch.object(f, 'is_admin', return_value=False), \
                 patch.object(f.subprocess, 'run', side_effect=fake_run), \
                 patch.object(f.time, 'sleep'):
                self.assertTrue(f.ensure_burn_mode())
            reset_mock.assert_called()
            self.assertTrue(any('--usb-boot-step' in c for c in calls))
            self.assertTrue(any(len(c) >= 2 and c[1] == 'bl2_boot' for c in calls))

    def test_resolve_misc_image_selects_revision_specific_image(self):
        p_0008 = f.resolve_misc_image('0008')
        p_00052 = f.resolve_misc_image('00052')
        p_00053 = f.resolve_misc_image('00053')
        p_auto = f.resolve_misc_image('auto')
        self.assertEqual(os.path.basename(p_0008), 'misc.img')
        self.assertEqual(os.path.basename(p_00052), 'misc.img')
        self.assertEqual(os.path.basename(p_00053), 'misc_00053.img')
        self.assertEqual(os.path.basename(p_auto), 'misc_00053.img')

if __name__ == '__main__': unittest.main()
