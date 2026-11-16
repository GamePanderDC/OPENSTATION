import importlib.util
import os
import tempfile
import types
import unittest
from contextlib import contextmanager
from unittest.mock import patch

PATH = os.path.join(os.path.dirname(__file__), "..", "service_flash", "flash_service.py")
spec = importlib.util.spec_from_file_location("flash_service_test", PATH)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class FlashWorkflowTests(unittest.TestCase):
    def test_partition_rejects_failed_worldcup_status(self):
        class Device:
            def start_download(self, *args, **kwargs): return b"success"
            def write_download_block(self, *args, **kwargs): pass
            def bulkCmd(self, *args, **kwargs): return b"failed"

        @contextmanager
        def opened():
            yield Device()

        with tempfile.TemporaryDirectory() as path:
            image = os.path.join(path, "system.img")
            with open(image, "wb") as f:
                f.write(b"test image")
            with patch.object(engine, "open_worldcup", opened):
                with self.assertRaisesRegex(RuntimeError, "не подтвердил запись"):
                    engine.flash_partition("system", image, 0, 100)

    def test_commands_are_ordered_and_no_reset_or_erase(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False,
                                         revision="auto", keep_data=True)
            events = []
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "run_after_ubt", side_effect=lambda: events.append("after_ubt.bat")), \
                 patch.object(engine, "aml_bulkcmd", side_effect=lambda cmd, **kw: events.append(cmd)), \
                 patch.object(engine, "flash_partition", side_effect=lambda name, *a: events.append(name)):
                engine.run_flash(args)
            self.assertEqual(events, ["mmc dev 1", "vbmeta", "vendor", "system", "misc", "after_ubt.bat"])

    def test_after_ubt_code_one_is_warning_but_other_errors_remain_errors(self):
        for code, expect_warning in ((0, False), (1, True)):
            proc = types.SimpleNamespace(returncode=code, communicate=lambda timeout: (b"", None))
            with self.subTest(code=code), patch.object(engine.subprocess, "Popen", return_value=proc), patch.object(engine, "emit"):
                self.assertEqual(engine.run_after_ubt(), expect_warning)
        proc = types.SimpleNamespace(returncode=2, communicate=lambda timeout: (b"", None))
        with patch.object(engine.subprocess, "Popen", return_value=proc), patch.object(engine, "emit"):
            with self.assertRaisesRegex(RuntimeError, "код 2"):
                engine.run_after_ubt()

    def test_warning_finishes_after_successful_partition_writes(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False, revision="auto", keep_data=True)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "aml_bulkcmd"), \
                 patch.object(engine, "flash_partition"), \
                 patch.object(engine, "run_after_ubt", return_value=True), \
                 patch.object(engine, "emit") as emit:
                self.assertTrue(engine.run_flash(args))
                self.assertTrue(any("не проверена" in call.args[0] for call in emit.call_args_list))

    def test_checksum_rejection_precedes_flash_commands(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "aml_bulkcmd") as command:
                with self.assertRaises(RuntimeError):
                    engine.run_flash(args)
                command.assert_not_called()

    def test_finalization_failure_propagates(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "aml_bulkcmd"), \
                 patch.object(engine, "flash_partition"), \
                 patch.object(engine, "run_after_ubt", side_effect=RuntimeError("failure")):
                with self.assertRaisesRegex(RuntimeError, "failure"):
                    engine.run_flash(args)


if __name__ == "__main__":
    unittest.main()
