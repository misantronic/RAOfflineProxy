# LZMA SDK 25.01 (7z container reader)

The subset of Igor Pavlov's 7-Zip C sources needed to read `.7z` archives:
open the archive, enumerate its entries, and decompress one of them
(LZMA, LZMA2, PPMd, BCJ/BCJ2/ARM/ARM64/Delta filters, plus stored files).

Used by `third_party/rcheevos_glue/sevenzip.c`, which extracts the single
console ROM out of a `.7z` so rc_hash can hash its contents. Without it a
`.7z` falls back to rc_hash's arcade rule, which hashes the filename.

- Source: https://github.com/ip7z/7zip, tag `25.01`, commit `5e96a827`
- Files: the `C/` directory's transitive closure from `7zArcIn.c`, `7zDec.c`,
  `7zFile.c` and their filter/codec dependencies (36 files, no encryption,
  no compressor)
- License: public domain. Every vendored file states it in its header; see
  `LICENSE.txt` for 7-Zip's overall license breakdown.

`libchdr/deps/lzma-25.01` also vendors `LzmaDec` from this same release, but it
renames its symbols to `CHDR_*` (see that copy's `include/LzmaDec.h`), so the
two coexist in one shared library without colliding.
