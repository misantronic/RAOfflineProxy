import unittest
from unittest import mock

from linux.raofflineproxy import proxy_service


class RetryStorageCorruptionReportTests(unittest.TestCase):
    def test_noop_when_no_incident(self) -> None:
        with (
            mock.patch.object(proxy_service.storage_corruption, "load_incident", return_value=None),
            mock.patch.object(proxy_service.log_uploader, "report_storage_corruption") as report,
        ):
            proxy_service.retry_storage_corruption_report()

        report.assert_not_called()

    def test_noop_when_already_reported(self) -> None:
        incident = {"reported": True, "upload_id": "abc123"}
        with (
            mock.patch.object(proxy_service.storage_corruption, "load_incident", return_value=incident),
            mock.patch.object(proxy_service.log_uploader, "report_storage_corruption") as report,
        ):
            proxy_service.retry_storage_corruption_report()

        report.assert_not_called()

    def test_marks_reported_on_success(self) -> None:
        incident = {"reported": False, "upload_id": None}
        with (
            mock.patch.object(proxy_service.storage_corruption, "load_incident", return_value=incident),
            mock.patch.object(
                proxy_service.log_uploader, "report_storage_corruption", return_value="abc123"
            ),
            mock.patch.object(proxy_service.storage_corruption, "mark_reported") as mark_reported,
        ):
            proxy_service.retry_storage_corruption_report()

        mark_reported.assert_called_once_with("abc123")

    def test_leaves_unreported_on_failure_for_next_retry(self) -> None:
        incident = {"reported": False, "upload_id": None}
        with (
            mock.patch.object(proxy_service.storage_corruption, "load_incident", return_value=incident),
            mock.patch.object(
                proxy_service.log_uploader,
                "report_storage_corruption",
                side_effect=RuntimeError("offline"),
            ),
            mock.patch.object(proxy_service.storage_corruption, "mark_reported") as mark_reported,
        ):
            proxy_service.retry_storage_corruption_report()

        mark_reported.assert_not_called()


if __name__ == "__main__":
    unittest.main()
