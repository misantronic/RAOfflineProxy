Place a matching private runtime here when preparing an Onion build.

Expected path:

```text
runtime/bin/python3
```

The app launcher also accepts this raw extracted layout:

```text
runtime/python/bin/python3
```

The recommended runtime archive currently targeted by the bundle helper is:

```text
cpython-3.10.20+20260510-armv7-unknown-linux-gnueabihf-install_only_stripped.tar.gz
```

Use `./linux/onion/fetch_runtime.sh` to download that archive into the local runtime cache before running `./linux/onion/build_bundle.sh`.
