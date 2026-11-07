from contextlib import ExitStack
from unittest.mock import patch

from linux.raofflineproxy import platforms


def use_platform(test, platform=None) -> None:
    """Make platforms.current() answer `platform` (the generic platform when None) for the
    length of `test`, whatever the machine running the tests looks like."""
    stack = ExitStack()
    test.addCleanup(stack.close)
    for candidate in platforms.PLATFORMS:
        stack.enter_context(
            patch.object(candidate, "detect", return_value=candidate is platform)
        )
