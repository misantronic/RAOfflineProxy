import unittest

from linux.raofflineproxy import config, utils


class LinuxUtilsRedactTests(unittest.TestCase):
    def test_redact_query_tokens_redacts_token(self) -> None:
        self.assertEqual(
            utils.redact_query_tokens("r=login2&u=user&t=secret"),
            "r=login2&u=user&t=<token>",
        )

    def test_redact_query_tokens_redacts_password(self) -> None:
        self.assertEqual(
            utils.redact_query_tokens("r=login2&u=user&p=hunter2"),
            "r=login2&u=user&p=<token>",
        )

    def test_redact_query_tokens_redacts_both_token_and_password(self) -> None:
        self.assertEqual(
            utils.redact_query_tokens("r=login2&u=user&p=hunter2&t=secret"),
            "r=login2&u=user&p=<token>&t=<token>",
        )

    def test_redact_query_tokens_leaves_value_without_secrets_unchanged(self) -> None:
        value = "r=login2&u=user"
        self.assertEqual(utils.redact_query_tokens(value), value)

    def test_redact_form_tokens_redacts_token(self) -> None:
        self.assertEqual(
            utils.redact_form_tokens("r=login2&u=user&t=secret"),
            "r=login2&u=user&t=%3Ctoken%3E",
        )

    def test_redact_form_tokens_redacts_password(self) -> None:
        self.assertEqual(
            utils.redact_form_tokens("r=login2&u=user&p=hunter2"),
            "r=login2&u=user&p=%3Ctoken%3E",
        )

    def test_redact_form_tokens_redacts_both_token_and_password(self) -> None:
        self.assertEqual(
            utils.redact_form_tokens("r=login2&u=user&p=hunter2&t=secret"),
            "r=login2&u=user&p=%3Ctoken%3E&t=%3Ctoken%3E",
        )


class LinuxSelfUserAgentTests(unittest.TestCase):
    def test_self_user_agent_leads_with_an_accepted_client(self) -> None:
        self.assertTrue(utils.self_user_agent().startswith(config.FALLBACK_USER_AGENT))

    def test_self_user_agent_carries_the_proxy_tag(self) -> None:
        self.assertIn(config.PROXY_UA_TAG, utils.self_user_agent())


if __name__ == "__main__":
    unittest.main()
