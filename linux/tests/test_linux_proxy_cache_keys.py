import unittest

from linux.raofflineproxy import proxy_service


class LinuxProxyCacheKeyTests(unittest.TestCase):
    def test_login2_uses_user_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=login2&u=misantronic&p=token",
        )

        self.assertEqual(key, "login2::misantronic")

    def test_patch_uses_patch_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=patch&u=misantronic&t=token&g=10701",
        )

        self.assertEqual(key, "patch:10701:misantronic")

    def test_unlocks_uses_softcore_unlocks_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php?r=unlocks&g=10701&h=0&u=misantronic&t=token",
            "",
        )

        self.assertEqual(key, "unlocks:10701:misantronic:0")

    def test_should_cache_action_allows_login2(self) -> None:
        self.assertTrue(proxy_service.should_cache_action("login2", "/dorequest.php"))

    def test_should_cache_action_allows_non_allowlisted_dorequest_actions(self) -> None:
        self.assertTrue(
            proxy_service.should_cache_action("somefutureaction", "/dorequest.php")
        )

    def test_should_cache_action_rejects_startsession(self) -> None:
        self.assertFalse(
            proxy_service.should_cache_action("startsession", "/dorequest.php")
        )

    def test_should_cache_action_rejects_non_dorequest_path(self) -> None:
        self.assertFalse(proxy_service.should_cache_action("badge", "/Badge/12345.png"))


if __name__ == "__main__":
    unittest.main()
