import unittest
from pathlib import Path

LINUX_DIR = Path(__file__).resolve().parents[1]
REPLACING_INSTALLERS = ("knulli", "rocknix", "darkos")


class InstallerReplacesTheAppTests(unittest.TestCase):
    """The installers once copied a release over the old one, so modules a release removed
    or moved stayed behind in the app folder. They must clear app/ and lib/ after the old
    proxy is stopped and before the new files are copied."""

    def _lines(self, device: str) -> list[str]:
        return (LINUX_DIR / device / "scripts" / "install.sh").read_text(encoding="utf-8").splitlines()

    def _index(self, lines: list[str], needle: str) -> int:
        matches = [i for i, line in enumerate(lines) if needle in line and not line.lstrip().startswith("#")]
        self.assertTrue(matches, needle)
        return matches[0]

    def test_app_and_lib_are_cleared_between_stopping_and_copying(self) -> None:
        for device in REPLACING_INSTALLERS:
            with self.subTest(device=device):
                lines = self._lines(device)
                stopped = self._index(lines, "stop-proxy")
                cleared = self._index(lines, 'rm -rf "${APP_DIR}" "${LIB_DIR}"')
                copied_app = self._index(lines, 'cp -r "${SCRIPT_DIR}/app/"*')
                copied_lib = self._index(lines, 'cp -r "${SCRIPT_DIR}/lib/"*')
                self.assertLess(stopped, cleared)
                self.assertLess(cleared, copied_app)
                self.assertLess(cleared, copied_lib)

    def test_the_folders_exist_again_before_the_copy(self) -> None:
        for device in REPLACING_INSTALLERS:
            with self.subTest(device=device):
                lines = self._lines(device)
                cleared = self._index(lines, 'rm -rf "${APP_DIR}" "${LIB_DIR}"')
                copied = self._index(lines, 'cp -r "${SCRIPT_DIR}/app/"*')
                between = "\n".join(lines[cleared:copied])
                self.assertIn('mkdir -p "${APP_DIR}"', between)
                self.assertIn('mkdir -p "${LIB_DIR}"', between)

    def test_user_data_is_not_inside_the_cleared_folders(self) -> None:
        for device in REPLACING_INSTALLERS:
            with self.subTest(device=device):
                text = (LINUX_DIR / device / "scripts" / "install.sh").read_text(encoding="utf-8")
                self.assertIn('APP_DIR="${BASE_DIR}/app"', text)
                self.assertIn('LIB_DIR="${BASE_DIR}/lib"', text)
                config_lines = [line for line in text.splitlines() if "/.config/raofflineproxy" in line]
                for line in config_lines:
                    self.assertNotIn("${BASE_DIR}", line)


if __name__ == "__main__":
    unittest.main()
