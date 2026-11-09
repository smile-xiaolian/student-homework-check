"""Create the object storage bucket if it does not exist, then exit.

Replaces the historical `minio/mc` based init container: MinIO withdrew its
community images from Docker Hub and Quay in late 2025, so the bucket is now
provisioned with boto3, which is already a dependency of this image.
"""

from __future__ import annotations

import os
import sys
import time

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

RETRY_SECONDS = 2.0


def _env(name: str, default: str) -> str:
    value = os.getenv(name, "").strip()
    return value or default


def main() -> int:
    endpoint = _env("STORAGE_ENDPOINT", "http://localhost:9000")
    access_key = _env("STORAGE_ACCESS_KEY", "edu_minio")
    secret_key = _env("STORAGE_SECRET_KEY", "change-me-minio-password")
    bucket = _env("STORAGE_BUCKET", "edu-submission")
    region = _env("STORAGE_REGION", "us-east-1")
    timeout_seconds = float(_env("INIT_TIMEOUT_SECONDS", "180"))

    client = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name=region,
        config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 2}),
    )

    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            client.head_bucket(Bucket=bucket)
            print(f"bucket {bucket} already exists at {endpoint}", flush=True)
            return 0
        except ClientError as exc:
            code = exc.response.get("Error", {}).get("Code", "")
            if code not in ("404", "NoSuchBucket", "NotFound", "400"):
                raise
            try:
                client.create_bucket(Bucket=bucket)
                print(f"created bucket {bucket} at {endpoint}", flush=True)
                return 0
            except ClientError as create_exc:
                code = create_exc.response.get("Error", {}).get("Code", "")
                if code != "BucketAlreadyOwnedByYou":
                    raise
                print(f"bucket {bucket} already exists at {endpoint}", flush=True)
                return 0
        except BotoCoreError:
            pass  # storage not reachable yet, retry below

        if time.monotonic() >= deadline:
            print(f"object storage at {endpoint} not ready within {timeout_seconds}s", file=sys.stderr, flush=True)
            return 1
        print(f"waiting for object storage at {endpoint} ...", flush=True)
        time.sleep(RETRY_SECONDS)


if __name__ == "__main__":
    raise SystemExit(main())
