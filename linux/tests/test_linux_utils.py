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

    def test_redact_query_tokens_keeps_keys_that_merely_end_in_t_or_p(self) -> None:
        # A bare substring match fires on the "t=" inside host=/port=/checked_at= and,
        # with no "&" left on the line to stop at, swallows the rest of it.
        for line in (
            "Proxy listening host=127.0.0.1 port=8099",
            "Update check cache hit platform=spruce checked_at=1786974106",
            "Service started at=now group=1",
        ):
            self.assertEqual(utils.redact_query_tokens(line), line)

    def test_redact_query_tokens_still_redacts_inside_a_log_line(self) -> None:
        self.assertEqual(
            utils.redact_query_tokens(
                "Request: POST /dorequest.php body=r=ping&u=bob&t=secret&g=2593"
            ),
            "Request: POST /dorequest.php body=r=ping&u=bob&t=<token>&g=2593",
        )

    def test_redact_query_tokens_redacts_after_a_query_marker(self) -> None:
        self.assertEqual(
            utils.redact_query_tokens("/dorequest.php?t=secret&g=99"),
            "/dorequest.php?t=<token>&g=99",
        )

    def test_redact_query_tokens_redacts_at_the_start_of_a_value(self) -> None:
        self.assertEqual(utils.redact_query_tokens("t=secret"), "t=<token>")

    def test_redact_query_tokens_covers_delimiters_other_than_query_separators(self) -> None:
        # These run over free-text log lines, where a secret can follow a quote or comma
        # rather than an "&". Narrowing to "?&" would silently stop redacting those.
        for line, expected in (
            ('quoted "t=secret"', 'quoted "t=<token>"'),
            ("csv u=bob,t=secret", "csv u=bob,t=<token>"),
            ("single 't=secret'", "single 't=<token>'"),
            ("list [t=secret]", "list [t=<token>]"),
            ("paren (p=secret)", "paren (p=<token>)"),
            ("semi a=1;t=secret", "semi a=1;t=<token>"),
        ):
            self.assertEqual(utils.redact_query_tokens(line), expected)

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
