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

    def test_after_ubt_runs_only_after_all_partitions_succeed(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ('vbmeta.img', 'vendor.img', 'system.img'):
                open(os.path.join(folder, name), 'wb').close()
            args = argparse.Namespace(rom_dir=folder)
            calls = []
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'is_uboot_alive', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=lambda name, *args: calls.append(name)), patch.object(f, 'run_after_ubt', side_effect=lambda: calls.append('after_ubt')):
                f.run_flash(args)
            self.assertEqual(calls, ['vbmeta', 'vendor', 'system', 'after_ubt'])
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'is_uboot_alive', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=RuntimeError('failed')), patch.object(f, 'run_after_ubt') as finish:
                with self.assertRaises(RuntimeError): f.run_flash(args)
                finish.assert_not_called()

if __name__ == '__main__': unittest.main()
