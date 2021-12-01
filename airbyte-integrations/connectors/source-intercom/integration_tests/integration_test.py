#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#

import json
import time
from copy import deepcopy
from pathlib import Path
from typing import Mapping

import pytest
from airbyte_cdk import AirbyteLogger
from airbyte_cdk.models import SyncMode
from requests.exceptions import HTTPError
from source_intercom.source import Companies, SourceIntercom, VersionApiAuthenticator

LOGGER = AirbyteLogger()
# from unittest.mock import Mock

HERE = Path(__file__).resolve().parent


@pytest.fixture(scope="module")
def stream_attributes() -> Mapping[str, str]:
    filename = HERE.parent / "secrets/config.json"
    with open(filename) as json_file:
        return json.load(json_file)


@pytest.mark.parametrize(
    "version,not_supported_streams",
    (
        (1.0, ["company_segments", "company_attributes", "contact_attributes"]),
        (1.1, ["company_segments", "company_attributes", "contact_attributes"]),
        (1.2, ["company_segments", "company_attributes", "contact_attributes"]),
        (1.3, ["company_segments", "company_attributes", "contact_attributes"]),
        (1.4, ["company_segments"]),
        (2.0, []),
        (2.1, []),
        (2.2, []),
        (2.3, []),
    ),
)
def test_supported_versions(stream_attributes, version, not_supported_streams):
    class CustomVersionApiAuthenticator(VersionApiAuthenticator):
        relevant_supported_version = str(version)

    authenticator = CustomVersionApiAuthenticator(token=stream_attributes["access_token"])
    for stream in SourceIntercom().streams(deepcopy(stream_attributes)):
        stream._authenticator = authenticator

        slices = list(stream.stream_slices(sync_mode=SyncMode.full_refresh))
        if stream.name in not_supported_streams:
            LOGGER.info(f"version {version} shouldn't be supported the stream '{stream.name}'")
            with pytest.raises(HTTPError) as err:
                next(stream.read_records(sync_mode=None, stream_slice=slices[0]), None)
            # example of response errors:
            # {"type": "error.list", "request_id": "000hjqhpf95ef3b8f8v0",
            #  "errors": [{"code": "intercom_version_invalid", "message": "The requested version could not be found"}]}
            assert len(err.value.response.json()["errors"]) > 0
            err_data = err.value.response.json()["errors"][0]
            LOGGER.info(f"version {version} doesn't support the stream '{stream.name}', error: {err_data}")
        else:
            LOGGER.info(f"version {version} should be supported the stream '{stream.name}'")
            records = stream.read_records(sync_mode=None, stream_slice=slices[0])
            if stream.name == "companies":
                # need to read all records for scroll resetting
                list(records)
            else:
                next(records, None)


def test_companies_scroll(stream_attributes):
    authenticator = VersionApiAuthenticator(token=stream_attributes["access_token"])
    stream1 = Companies(authenticator=authenticator)
    stream2 = Companies(authenticator=authenticator)
    stream3 = Companies(authenticator=authenticator)

    # read the first stream and stop
    next(stream1.read_records(sync_mode=SyncMode.full_refresh))

    start_time = time.time()
    # read all records
    records = list(stream2.read_records(sync_mode=SyncMode.full_refresh))
    assert len(records) == 3
    assert (time.time() - start_time) > 60.0

    start_time = time.time()
    # read all records again
    records = list(stream3.read_records(sync_mode=SyncMode.full_refresh))
    assert len(records) == 3
    assert (time.time() - start_time) < 1.0
