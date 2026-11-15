import base64
import hashlib
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SF = ROOT / "service_flash"
POST_SH = ROOT / "scripts" / "post_fix_misc.sh"
MISC_INC = ROOT / "daemon" / "src" / "hw" / "misc_repair_data.inc"


class MiscPostFixAssetsTests(unittest.TestCase):
    def test_post_fix_misc_sh_has_lf_and_exact_image_hashes(self):
        raw = POST_SH.read_bytes()
        self.assertNotIn(b"\r\n", raw, "post_fix_misc.sh must use LF line endings for mksh")
        text = raw.decode("utf-8")

        m_intel = re.search(r"B64_INTEL_HDR='([^']+)'", text)
        m_00053 = re.search(r"B64_00053_HDR='([^']+)'", text)
        m_sha_i = re.search(r"SHA_INTEL_1M='([0-9a-f]{64})'", text)
        m_sha_5 = re.search(r"SHA_00053_1M='([0-9a-f]{64})'", text)
        self.assertIsNotNone(m_intel)
        self.assertIsNotNone(m_00053)
        self.assertIsNotNone(m_sha_i)
        self.assertIsNotNone(m_sha_5)

        intel_bytes = base64.b64decode(m_intel.group(1))
        m53_bytes = base64.b64decode(m_00053.group(1))
        self.assertEqual(len(intel_bytes), 1580)
        self.assertEqual(len(m53_bytes), 1580)

        exp_intel = (SF / "misc.img").read_bytes()[:1048576]
        exp_m53 = (SF / "misc_00053.img").read_bytes()[:1048576]
        self.assertEqual(intel_bytes + b"\x00" * (1048576 - 1580), exp_intel)
        self.assertEqual(m53_bytes + b"\x00" * (1048576 - 1580), exp_m53)
        self.assertEqual(hashlib.sha256(exp_intel).hexdigest(), m_sha_i.group(1))
        self.assertEqual(hashlib.sha256(exp_m53).hexdigest(), m_sha_5.group(1))

    def test_cpp_inc_matches_service_flash_images(self):
        inc = MISC_INC.read_text(encoding="utf-8")
        sha_i = hashlib.sha256((SF / "misc.img").read_bytes()[:1048576]).hexdigest()
        sha_5 = hashlib.sha256((SF / "misc_00053.img").read_bytes()[:1048576]).hexdigest()
        self.assertIn(sha_i, inc)
        self.assertIn(sha_5, inc)

    def test_windows_wrapper_and_ps1_exist(self):
        bat = (ROOT / "POST_FIX_MISC.bat").read_text(encoding="utf-8")
        ps1 = (ROOT / "scripts" / "post_fix_misc.ps1").read_text(encoding="utf-8")
        self.assertIn("post_fix_misc.ps1", bat)
        self.assertIn("post_fix_misc.sh", ps1)


if __name__ == "__main__":
    unittest.main()
